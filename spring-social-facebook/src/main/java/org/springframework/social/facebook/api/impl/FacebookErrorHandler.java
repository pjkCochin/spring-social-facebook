/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.social.facebook.api.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.social.ExpiredAuthorizationException;
import org.springframework.social.InsufficientPermissionException;
import org.springframework.social.InvalidAuthorizationException;
import org.springframework.social.ResourceNotFoundException;
import org.springframework.social.UncategorizedApiException;
import org.springframework.social.facebook.api.NotAFriendException;
import org.springframework.social.facebook.api.ResourceOwnershipException;
import org.springframework.web.client.DefaultResponseErrorHandler;

/**
 * Subclass of {@link DefaultResponseErrorHandler} that handles errors from Facebook's
 * Graph API, interpreting them into appropriate exceptions.
 * @author Craig Walls
 */
class FacebookErrorHandler extends DefaultResponseErrorHandler {

	@Override
	public void handleError(ClientHttpResponse response) throws IOException {				
		Map<String, String> errorDetails = extractErrorDetailsFromResponse(response);
		if (errorDetails == null) {
			handleUncategorizedError(response, errorDetails);
		}

		// 401 is the only status code we can trust from Facebook. 
		// Facebook is very inconsistent in use of error codes in most other cases.
		if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
			throw new InvalidAuthorizationException(); // TODO: could the message help us choose an ExpiredCredentialsException instead?
		}

		handleFacebookError(errorDetails);
		
		// if not otherwise handled, do default handling and wrap with UncategorizedApiException
		handleUncategorizedError(response, errorDetails);			
	}

	private void handleUncategorizedError(ClientHttpResponse response, Map<String, String> errorDetails) {
		try {
			super.handleError(response);
		} catch (Exception e) {
			if (errorDetails != null) {
				throw new UncategorizedApiException(errorDetails.get("message"), e);
			} else {
				throw new UncategorizedApiException("No error details from Facebook", e);
			}
		}
	}
	
	@Override 
	public boolean hasError(ClientHttpResponse response) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody()));
		return super.hasError(response) || (reader.ready() && reader.readLine().startsWith("{\"error\":"));
	}

	/**
	 * Examines the error data returned from Facebook and throws the most applicable exception.
	 * @param errorDetails a Map containing a "type" and a "message" corresponding to the Graph API's error response structure.
	 */
	void handleFacebookError(Map<String, String> errorDetails) {
		// Can't trust the type to be useful. It's often OAuthException, even for things not OAuth-related.
		// Can rely only on the message (which itself isn't very consistent).
		String message = errorDetails.get("message");
		if (message.contains("Requires extended permission")) {
			String requiredPermission = message.split(": ")[1];
			throw new InsufficientPermissionException(requiredPermission);
		} else if (message.equals("The member must be a friend of the current user.")) {
			throw new NotAFriendException(message);
		} else if (message.contains("Unknown path components")) {
			throw new ResourceNotFoundException(message);
		} else if (message.equals("User must be an owner of the friendlist")) { // watch for pattern in similar message in other resources
			throw new ResourceOwnershipException(message);
		} else if (message.contains("Some of the aliases you requested do not exist")) {
			throw new ResourceNotFoundException(message);
		} else if (message.contains("Session has expired")) {
			throw new ExpiredAuthorizationException();
		} else if (message.equals("The session has been invalidated because the user has changed the password.")) {
			throw new InvalidAuthorizationException();
		} else if (message.contains("has not authorized application")) {
			throw new InvalidAuthorizationException();
		} else if (message.equals("Error validating access token: The session is invalid because the user logged out.")) {
			throw new InvalidAuthorizationException();
		}
	}

	/*
	 * Attempts to extract Facebook error details from the response.
	 * Returns null if the response doesn't match the expected JSON error response.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, String> extractErrorDetailsFromResponse(ClientHttpResponse response) throws IOException {
		ObjectMapper mapper = new ObjectMapper(new JsonFactory());		
		try {
		    Map<String, Object> responseMap = mapper.<Map<String, Object>>readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
		    if (responseMap.containsKey("error")) {
		    	return (Map<String, String>) responseMap.get("error");
		    }
		} catch (JsonParseException e) {
			return null;
		}
	    return null;
	}
}
