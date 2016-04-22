package com.q4.rest.client.jersey;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.config.ClientConfig;

/**
 * Utility class to manage the jersey client creation.
 * <p/>
 * Whenever a default configuration is sufficient, use getWebResource method to
 * avoid costlier client object creation.
 * 
 * @author Qfor
 */
public final class ClientUtil {

	/**
	 * Create and return a new client instance with a default client
	 * configuration.
	 */
	public static Client createClient() {
		return new Client(new CustomURLConnectionClientHandler());
	}

	/**
	 * Create and return a new client instance with a client configuration.
	 * 
	 * @param config
	 *            the client configuration.
	 */
	public static Client createClient(final ClientConfig config) {
		return new Client(new CustomURLConnectionClientHandler(), config);
	}
}
