package standup.connector.rally;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.AbstractHttpClient;

import standup.Utilities;
import standup.connector.ConnectorException;
import standup.connector.HttpClientFactory;
import standup.connector.UnexpectedResponseException;

import com.rallydev.xml.DomainObjectType;
import com.rallydev.xml.QueryResultType;


/**
 * A connection to the Rally Server.
 * The connection maintains the authorization information for the
 * session along with the working set of HTTP headers.
 */
public class ServerConnection
	implements standup.connector.ServerConnection,
	           org.apache.http.client.CredentialsProvider
{
	private String userName;
	private String password;
	private final HttpHost host;
	private final HttpClientFactory clientFactory;
	private JAXBContext jaxb;
	private Unmarshaller unmarshaller;

	public ServerConnection(String serverName, HttpClientFactory clientFactory)
	{
		this.userName = "";
		this.password = "";
		this.host = new HttpHost(serverName, 443, "https");
		this.clientFactory = clientFactory;
		try {
			this.jaxb = JAXBContext.newInstance("com.rallydev.xml");
			this.unmarshaller = jaxb.createUnmarshaller();
		} catch (JAXBException e) {
			throw new Error("failed to initialize XML bindings", e);
		}
	}

	/* (non-Javadoc)
	 * @see standup.connector.ServerConnection#listIterationsForProject(java.lang.String)
	 */
	public List<IterationStatus> listIterationsForProject(String project)
		throws IOException, ClientProtocolException, ConnectorException, JAXBException
	{		
		QueryResultType result = doQuery("iteration", "Project.Name", "=", project);
		ArrayList<IterationStatus> iterations = new ArrayList<IterationStatus>(
				Math.min(result.getPageSize().intValue(),
						 result.getTotalResultCount().intValue()));
		for (DomainObjectType domainObj : result.getResults().getObject()) {
			IterationStatus iterStatus = new IterationStatus();
			iterStatus.iterationName = domainObj.getRefObjectName();
			try {
				iterStatus.iterationURI = new URI(domainObj.getRef());
			} catch (URISyntaxException e) {
				e.printStackTrace();
				iterStatus.iterationURI = null;
			}
			iterations.add(iterStatus);
		}
		return iterations;
	}

	/**
	 * Constructs a query string according to the Rally Grammar.
	 * 
	 * Rally queries are essentially Attribute OPERATOR Value triples
	 * strung together with AND and OR.  The expression is fully parenthesized
	 * according to a very specific grammar.  The following grammar was stolen
	 * from https://rally1.rallydev.com/slm/doc/webservice/introduction.jsp.
	 * 
	 * <table>
	 * <tr><td>QueryString</td><td>&#x2192;</td><td>( AttributeName SPACE AttributeOperator SPACE AttributeValue )</td></tr>
	 * <tr><td></td><td></td><td>( AttributePath SPACE AttributeOperator SPACE AttributeValue )</td></tr>
	 * <tr><td></td><td></td><td>( QueryString SPACE BooleanOperator SPACE QueryString )</td></tr>
	 * <tr><td>AttributeOperator</td><td>&#x2192;</td><td>=</td></tr>
	 * <tr><td></td><td></td><td>!=</td></tr>
	 * <tr><td></td><td></td><td>&gt;</td></tr>
	 * <tr><td></td><td></td><td>&lt;</td></tr>
	 * <tr><td></td><td></td><td>&gt;=</td></tr>
	 * <tr><td></td><td></td><td>&lt;=</td></tr>
	 * <tr><td></td><td></td><td>contains <i>(NOTE: Starting with version 1.18 the arguments are NOT case sensitive)</i></td></tr>
	 * <tr><td>BooleanOperator</td><td>&#x2192;</td><td>AND</td></tr>
	 * <tr><td></td><td></td><td>OR</td></tr>
	 * <tr><td>AttributeName</td><td>&#x2192;</td><td>The name of the attribute being queried. Name, Notes, etc...</td></tr>
	 * <tr><td>AttributePath</td><td>&#x2192;</td><td>The path to an attribute. For instance, when querying for tasks that are in a given iteration, it's possible to use the path "Card.Iteration" because Task has a "Card" attribute, and Card has an "Iteration" attribute.</td></tr>
	 * <tr><td>AttributeValue</td><td>&#x2192;</td><td>Some value. Strings with spaces must be double-quoted (single quotations are not allowed). Object references should be expressed as the REST URI for the object. Use "null" (double quotations optional) to query for a null value in an object reference, integer, decimal or date attribute.</td></tr>
	 * </table>
	 * 
	 * @param tokens
	 * @return a properly escaped string for use as an HTTP query string.
	 * @throws EncoderException
	 */
	private String buildQueryString(String... tokens) throws EncoderException {
		if (tokens.length < 3 || tokens.length%3 != 0) {
			throw new InvalidParameterException("tokens is a list of triples");
		}

		String querySegments[] = new String[tokens.length / 3];
		int index = 0;
		while (index < tokens.length) {
			querySegments[index/3] = String.format("%s %s \"%s\"",
					tokens[index], tokens[index+1], tokens[index+2]);
			index += 3;
		}

		String queryString = String.format("(%s)", querySegments[0]);
		for (index=1; index<querySegments.length; ++index) {
			queryString = String.format("(%s AND (%s))", queryString, querySegments[index]);
		}		
		URLCodec codec = new URLCodec("US-ASCII");
		return "query="+codec.encode(queryString);
	}

	public void setUsername(String userName) {
		this.userName = userName;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	//=========================================================================
	// CredentialsProvider implementation
	//
	public void clear() {
		this.userName = "";
		this.password = "";
	}

	public Credentials getCredentials(AuthScope scope) {
		return new UsernamePasswordCredentials(this.userName, this.password);
	}

	public void setCredentials(AuthScope scope, Credentials credentials) {
		setUsername(credentials.getUserPrincipal().getName());
		setPassword(credentials.getPassword());
	}

	//=========================================================================
	// Internal utility methods
	//
	private QueryResultType doQuery(String objectType, String... queryParams)
		throws ClientProtocolException, IOException, ConnectorException, JAXBException
	{
		String query = null;
		String path = Utilities.join("/", Constants.RALLY_BASE_RESOURCE,
				Constants.RALLY_API_VERSION, objectType);
		try {
			query = buildQueryString(queryParams);
			URI uri = Utilities.createURI(this.host, path, query);
			return retrieveURI(QueryResultType.class, uri);
		} catch (URISyntaxException e) {
			// Thrown by createURI if some portion of the URI is invalid.
			// Convert this to a MalformedURLException as well.
			throw Utilities.generateException(MalformedURLException.class, e,
					"failed to build URL", "object type", objectType, "query was", query);
		} catch (EncoderException e) {
			// Thrown by buildQueryString if the string cannot be encoded
			// by the URLCodec.  Convert this to a MalformedURLException.
			//
			// XXX if you really want to test this, the only way to get it
			//     to fire is to change the character set used by the URLCodec
			//     in buildQueryString to something unrecognized.
			throw Utilities.generateException(MalformedURLException.class, e,
					"failed to build query string for", (Object[])queryParams);
		}
	}

	public <T> T retrieveURI(Class<T> klass, URI uri)
		throws ClientProtocolException, IOException, UnexpectedResponseException
	{
		HttpGet get = new HttpGet(uri);
		AbstractHttpClient httpClient = clientFactory.getHttpClient(this);
		HttpResponse response = httpClient.execute(host, get);
		StatusLine status = response.getStatusLine();
		if (status.getStatusCode() == 200) {
			HttpEntity entity = response.getEntity();
			try {
				JAXBElement<?> responseObj = (JAXBElement<?>) unmarshaller.unmarshal(entity.getContent());
				if (responseObj.getDeclaredType() == klass) {
					@SuppressWarnings("unchecked")
					T result = (T) responseObj.getValue();
					return result;
				} else {
					throw Utilities.generateException(UnexpectedResponseException.class,
							"unexpected response type", "expected", klass.toString(),
							"got", responseObj.getDeclaredType().toString());
				}
			} catch (JAXBException e) {
				throw Utilities.generateException(UnexpectedResponseException.class, e,
						"failed to unmarshal response");
			}
		} else {
			throw new ClientProtocolException("request for "+uri.toString()+"failed");
		}
	}

}