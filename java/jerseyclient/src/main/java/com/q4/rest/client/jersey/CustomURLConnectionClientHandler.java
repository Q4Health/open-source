package com.q4.rest.client.jersey;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.ws.rs.core.MultivaluedMap;

import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.CommittingOutputStream;
import com.sun.jersey.api.client.TerminatingClientHandler;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.client.urlconnection.HTTPSProperties;
import com.sun.jersey.client.urlconnection.HttpURLConnectionFactory;
import com.sun.jersey.core.header.InBoundHeaders;

/**
 * @author Qfor
 */
public class CustomURLConnectionClientHandler extends TerminatingClientHandler {

	/**
	 * A value of "true" declares that the client will try to set unsupported
	 * HTTP method to HttpURLConnection via reflection. Enabling this feature
	 * might cause security related warnings/errors and it might break when
	 * other JDK implementation is used.
	 * <p/>
	 * Use only when you know what you are doing.
	 * <p/>
	 * The value MUST be an instance of {@link java.lang.Boolean}. If the
	 * property is absent then the default value is "false".
	 */
	public static final String PROPERTY_HTTP_URL_CONNECTION_SET_METHOD_WORKAROUND = "com.sun.jersey.client.property.httpUrlConnectionSetMethodWorkaround";

	private final class URLConnectionResponse extends ClientResponse {

		private final String method;

		private final HttpURLConnection uc;

		URLConnectionResponse(final int status, final InBoundHeaders headers,
				final InputStream entity, final String method,
				final HttpURLConnection uc) {
			super(status, headers, entity, getMessageBodyWorkers());
			this.method = method;
			this.uc = uc;
		}

		@Override
		public boolean hasEntity() {
			if ("HEAD".equals(method) || getEntityInputStream() == null) {
				return false;
			}

			final int l = uc.getContentLength();
			return l > 0 || l == -1;
		}

		@Override
		public String toString() {
			return uc.getRequestMethod() + " " + uc.getURL()
					+ " returned a response status of " + this.getStatus()
					+ " " + this.getClientResponseStatus();
		}
	}

	private HttpURLConnectionFactory httpURLConnectionFactory = null;

	/**
	 * Construct a new instance with an HTTP URL connection factory.
	 * 
	 * @param httpURLConnectionFactory
	 *            the HTTP URL connection factory.
	 */
	public CustomURLConnectionClientHandler(
			final HttpURLConnectionFactory httpURLConnectionFactory) {
		this.httpURLConnectionFactory = httpURLConnectionFactory;
	}

	public CustomURLConnectionClientHandler() {
		this(null);
	}

	/**
	 * ClientRequest handler.
	 * 
	 * @param ro
	 *            ClientRequest
	 * @return Server response represented as ClientResponse
	 */
	@Override
	public ClientResponse handle(final ClientRequest ro) {
		try {
			final ClientResponse response = _invoke(ro);
			return response;
		} catch (final Exception ex) {
			throw new ClientHandlerException(ex);
		}
	}

	private ClientResponse _invoke(final ClientRequest ro) throws IOException {
		final HttpURLConnection uc;

		if (this.httpURLConnectionFactory == null) {
			uc = (HttpURLConnection) ro.getURI().toURL().openConnection();
		} else {
			uc = this.httpURLConnectionFactory.getHttpURLConnection(ro.getURI()
					.toURL());
		}

		final Integer readTimeout = (Integer) ro.getProperties().get(
				ClientConfig.PROPERTY_READ_TIMEOUT);
		if (readTimeout != null) {
			uc.setReadTimeout(readTimeout);
		}

		final Integer connectTimeout = (Integer) ro.getProperties().get(
				ClientConfig.PROPERTY_CONNECT_TIMEOUT);
		if (connectTimeout != null) {
			uc.setConnectTimeout(connectTimeout);
		}

		final Boolean followRedirects = (Boolean) ro.getProperties().get(
				ClientConfig.PROPERTY_FOLLOW_REDIRECTS);
		if (followRedirects != null) {
			uc.setInstanceFollowRedirects(followRedirects);
		}

		if (uc instanceof HttpsURLConnection) {
			final HTTPSProperties httpsProperties = (HTTPSProperties) ro
					.getProperties().get(
							HTTPSProperties.PROPERTY_HTTPS_PROPERTIES);
			if (httpsProperties != null) {
				httpsProperties.setConnection((HttpsURLConnection) uc);
			}
		}

		final Boolean httpUrlConnectionSetMethodWorkaround = (Boolean) ro
				.getProperties().get(
						PROPERTY_HTTP_URL_CONNECTION_SET_METHOD_WORKAROUND);
		if (httpUrlConnectionSetMethodWorkaround != null
				&& httpUrlConnectionSetMethodWorkaround == true) {
			setRequestMethodUsingWorkaroundForJREBug(uc, ro.getMethod());
		} else {
			uc.setRequestMethod(ro.getMethod());
		}

		// Write the request headers
		writeOutBoundHeaders(ro.getHeaders(), uc);

		// Write the entity (if any)
		final Object entity = ro.getEntity();
		if (entity != null) {
			uc.setDoOutput(true);

			if (ro.getMethod().equalsIgnoreCase("GET")) {
			}

			writeRequestEntity(ro, new RequestEntityWriterListener() {
				@Override
				public void onRequestEntitySize(final long size) {
					if (size != -1 && size < Integer.MAX_VALUE) {
						// HttpURLConnection uses the int type for content
						// length
						uc.setFixedLengthStreamingMode((int) size);
					} else {
						// TODO it appears HttpURLConnection has some bugs in
						// chunked encoding
						// uc.setChunkedStreamingMode(0);
						final Integer chunkedEncodingSize = (Integer) ro
								.getProperties()
								.get(ClientConfig.PROPERTY_CHUNKED_ENCODING_SIZE);
						if (chunkedEncodingSize != null) {
							uc.setChunkedStreamingMode(chunkedEncodingSize);
						}
					}
				}

				@Override
				public OutputStream onGetOutputStream() throws IOException {
					return new CommittingOutputStream() {
						@Override
						protected OutputStream getOutputStream()
								throws IOException {
							return uc.getOutputStream();
						}

						@Override
						public void commit() throws IOException {
							writeOutBoundHeaders(ro.getHeaders(), uc);
						}
					};
				}
			});
		} else {
			writeOutBoundHeaders(ro.getHeaders(), uc);
		}

		// Return the in-bound response
		return new URLConnectionResponse(uc.getResponseCode(),
				getInBoundHeaders(uc), getInputStream(uc), ro.getMethod(), uc);
	}

	/**
	 * Workaround for a bug in
	 * <code>HttpURLConnection.setRequestMethod(String)</code> The
	 * implementation of Sun Microsystems is throwing a
	 * <code>ProtocolException</code> when the method is other than the HTTP/1.1
	 * default methods. So to use PROPFIND and others, we must apply this
	 * workaround.
	 * <p/>
	 * See issue http://java.net/jira/browse/JERSEY-639
	 */

	private static final void setRequestMethodUsingWorkaroundForJREBug(
			final HttpURLConnection httpURLConnection, final String method) {
		try {
			httpURLConnection.setRequestMethod(method); // Check whether we are
														// running on a buggy
														// JRE
		} catch (final ProtocolException pe) {
			try {
				final Class<?> httpURLConnectionClass = httpURLConnection
						.getClass();
				final Field methodField = httpURLConnectionClass
						.getSuperclass().getDeclaredField("method");
				methodField.setAccessible(true);
				methodField.set(httpURLConnection, method);
			} catch (final Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	private void writeOutBoundHeaders(
			final MultivaluedMap<String, Object> metadata,
			final HttpURLConnection uc) {
		for (final Map.Entry<String, List<Object>> e : metadata.entrySet()) {
			final List<Object> vs = e.getValue();
			if (vs.size() == 1) {
				uc.setRequestProperty(e.getKey(),
						ClientRequest.getHeaderValue(vs.get(0)));
			} else {
				final StringBuilder b = new StringBuilder();
				boolean add = false;
				for (final Object v : e.getValue()) {
					if (add) {
						b.append(',');
					}
					add = true;
					b.append(ClientRequest.getHeaderValue(v));
				}
				uc.setRequestProperty(e.getKey(), b.toString());
			}
		}
	}

	private InBoundHeaders getInBoundHeaders(final HttpURLConnection uc) {
		final InBoundHeaders headers = new InBoundHeaders();
		for (final Map.Entry<String, List<String>> e : uc.getHeaderFields()
				.entrySet()) {
			if (e.getKey() != null) {
				headers.put(e.getKey(), e.getValue());
			}
		}
		return headers;
	}

	private InputStream getInputStream(final HttpURLConnection uc)
			throws IOException {
		if (uc.getResponseCode() == 200) {
			return uc.getInputStream();
		}

		if (uc.getResponseCode() == 204 || uc.getResponseCode() == -1) {
			return new ByteArrayInputStream(new byte[0]);
		}

		final InputStream ein = uc.getErrorStream();
		return (ein != null) ? ein : new ByteArrayInputStream(new byte[0]);
	}
}
