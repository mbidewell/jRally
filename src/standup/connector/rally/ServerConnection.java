package standup.connector.rally;

import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.util.JAXBResult;
import javax.xml.bind.util.JAXBSource;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;

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
import org.apache.log4j.Logger;
import org.apache.log4j.NDC;

import standup.connector.ConnectorException;
import standup.connector.HttpClientFactory;
import standup.connector.UnexpectedResponseException;
import standup.utility.Utilities;
import standup.xml.Description;
import standup.xml.StoryList;
import standup.xml.StoryType;
import standup.xml.TaskList;

import com.rallydev.xml.ArtifactType;
import com.rallydev.xml.DefectType;
import com.rallydev.xml.DomainObjectType;
import com.rallydev.xml.HierarchicalRequirementType;
import com.rallydev.xml.QueryResultType;
import com.rallydev.xml.TaskType;


/**
 * A connection to the Rally Server.
 * The connection maintains the authorization information for the
 * session along with the working set of HTTP headers.
 */
public class ServerConnection
	implements standup.connector.ServerConnection,
	           org.apache.http.client.CredentialsProvider
{
	private static final Logger logger = Logger.getLogger(ServerConnection.class);
	private static final Pattern ltPattern = Pattern.compile("&lt;");
	private static final Pattern gtPattern = Pattern.compile("&gt;");
	private static final Pattern ampPattern = Pattern.compile("&amp;");
	private static final Pattern nbspPattern = Pattern.compile("&nbsp;");
	private static final Pattern brPattern = Pattern.compile("<br\\s*>");
	private static final Pattern ampPattern2 = Pattern.compile("&");

	private String userName;
	private String password;
	private final HttpHost host;
	private final HttpClientFactory clientFactory;
	private JAXBContext jaxb;
	private Unmarshaller unmarshaller;
	private final TransformerFactory xformFactory;
	private final standup.xml.ObjectFactory standupFactory;

	public ServerConnection(String serverName, HttpClientFactory clientFactory)
	{
		this.userName = "";
		this.password = "";
		this.host = new HttpHost(serverName, 443, "https");
		this.clientFactory = clientFactory;
		this.xformFactory = TransformerFactory.newInstance();
		this.standupFactory = new standup.xml.ObjectFactory();
		try {
			this.jaxb = JAXBContext.newInstance("com.rallydev.xml:standup.xml");
			this.unmarshaller = jaxb.createUnmarshaller();
		} catch (JAXBException e) {
			throw new Error("failed to initialize XML bindings", e);
		}
	}

	/* (non-Javadoc)
	 * @see standup.connector.ServerConnection#listIterationsForProject(java.lang.String)
	 */
	@Override
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
				logger.error(String.format("iteration %s has invalid URI %s", iterStatus.iterationName, domainObj.getRef()), e);
				iterStatus.iterationURI = null;
			}
			iterations.add(iterStatus);
		}
		return iterations;
	}

	protected void processQueryResult(StoryList stories, QueryResultType result)
		throws ClientProtocolException, UnexpectedResponseException, IOException, URISyntaxException, JAXBException, TransformerException
	{
		List<String> errors = result.getErrors().getOperationResultError();
		if (errors.size() > 0) {
			for (String err: errors) {
				logger.error(err);
			}
			return;
		}
		for (String warning: result.getWarnings().getOperationResultWarning()) {
			logger.warn(warning);
		}

		com.rallydev.xml.ObjectFactory rallyFactory = new com.rallydev.xml.ObjectFactory();
		List<DomainObjectType> domainObjects = result.getResults().getObject();
		for (DomainObjectType domainObj: domainObjects) {
			StoryType story = null;
			ArtifactType artifact = null;
			JAXBElement<? extends ArtifactType> obj = null;
			String stringType = domainObj.getType();
			if (stringType.equalsIgnoreCase("HierarchicalRequirement")) {
				if (domainObj instanceof HierarchicalRequirementType) { // xsi:type in use!
					obj = rallyFactory.createHierarchicalRequirement((HierarchicalRequirementType) domainObj);
					artifact = (HierarchicalRequirementType) domainObj;
				} else { // we need to fetch this explicitly
					obj = retrieveJAXBElement(HierarchicalRequirementType.class, new URI(domainObj.getRef()));
					artifact = obj.getValue();
				}
			} else if (stringType.equalsIgnoreCase("Defect")) {
				if (domainObj instanceof DefectType) {
					obj = rallyFactory.createDefect((DefectType) domainObj);
					artifact = (DefectType) domainObj;
				} else {
					obj = retrieveJAXBElement(DefectType.class, new URI(domainObj.getRef()));
					artifact = obj.getValue();
				}
			}
	
			if (artifact != null) {
				story = this.transformResultInto(StoryType.class, obj);
				story.setDescription(fixDescription(artifact));
				stories.getStory().add(story);
			} else {
				logger.debug(String.format("ignoring DomainObject %d of type %s", domainObj.getObjectID(), stringType));
			}
		}
	}

	/* (non-Javadoc)
	 * @see standup.connector.ServerConnection#retrieveStories(java.lang.String[])
	 */
	@Override
	public StoryList retrieveStories(String[] stories)
		throws IOException, ClientProtocolException, ConnectorException
	{
		StringBuilder builder = new StringBuilder();
		List<String> segments = new ArrayList<String>(stories.length);
		for (String storyID: stories) {
			segments.add(String.format("FormattedID = \"%s\"", storyID));
			builder.append("(");
		}

		Iterator<String> iter = segments.iterator();
		builder.append(iter.next()).append(")");
		while (iter.hasNext()) {
			builder.append(" OR (").append(iter.next()).append("))");
		}
		if (logger.isDebugEnabled()) logger.debug("query="+builder.toString());

		StoryList storyList = this.standupFactory.createStoryList();
		try {
			URLCodec codec = new URLCodec("US-ASCII");
			String query = "query="+codec.encode(builder.toString());
			// + "&fetch=true";
			// This is not supported yet... each sub-object will result in another
			// HTTP request.  If the response included xsi:type, then we could
			// fetch everything in one request and JAXB would correctly detect the
			// types for us...
			String path = Utilities.join("/", Constants.RALLY_BASE_RESOURCE,
					Constants.RALLY_API_VERSION, "artifact");
			URI uri = Utilities.createURI(this.host, path, query);
			QueryResultType result = retrieveURI(QueryResultType.class, uri);
			processQueryResult(storyList, result);
		} catch (JAXBException e) {
			logger.error("JAXB related error while retrieving multiple stories", e);
		} catch (TransformerException e) {
			logger.error("XSLT related error while retrieving multiple stories", e);
		} catch (URISyntaxException e) {
			logger.error(e.getClass().getCanonicalName(), e);
		} catch (EncoderException e) {
			logger.error(e.getClass().getCanonicalName(), e);
		}
		
		return storyList;
	}

	/* (non-Javadoc)
	 * @see standup.connector.ServerConnection#retrieveStoriesForIteration(java.lang.String)
	 */
	@Override
	public StoryList retrieveStoriesForIteration(String iteration)
		throws IOException, ClientProtocolException, ConnectorException
	{
		StoryList storyList = this.standupFactory.createStoryList();
		try {
			NDC.push("retrieving stories for iteration "+iteration);
			QueryResultType result = doQuery("hierarchicalrequirement", "Iteration.Name", "=", iteration);
			processQueryResult(storyList, result);
			NDC.pop();

			NDC.push("retrieving defects for iteration "+iteration);
			result = doQuery("defect", "Iteration.Name", "=", iteration);
			processQueryResult(storyList, result);
		} catch (JAXBException e) {
			logger.error("JAXB related error while processing iteration "+iteration, e);
		} catch (TransformerException e) {
			logger.error("XSLT related error while processing iteration "+iteration, e);
		} catch (URISyntaxException e) {
			logger.error(e.getClass().getCanonicalName(), e);
		} finally {
			NDC.pop();
		}
		return storyList;
	}

	/* (non-Javadoc)
	 * @see standup.connector.ServerConnection#retrieveTasks(standup.xml.StoryList)
	 */
	@Override
	public TaskList retrieveTasks(StoryList stories)
		throws IOException, ClientProtocolException, ConnectorException
	{
		TaskList taskList = this.standupFactory.createTaskList();
		for (StoryType story: stories.getStory()) {
			String storyID = story.getIdentifier();
			try {
				NDC.push("retrieving tasks for "+story.getIdentifier());
				logger.debug(NDC.peek());
				QueryResultType result = doQuery("task", "WorkProduct.FormattedID", "=", storyID);
				for (DomainObjectType domainObj: result.getResults().getObject()) {
					JAXBElement<TaskType> taskObj = this.retrieveJAXBElement(TaskType.class, new URI(domainObj.getRef()));
					standup.xml.TaskType task = this.transformResultInto(standup.xml.TaskType.class, taskObj);
					task.setParentIdentifier(storyID);
					taskList.getTask().add(task);
				}
			} catch (JAXBException e) {
				logger.error("JAXB related error while processing story "+storyID, e);
			} catch (TransformerException e) {
				logger.error("XSLT related error while processing story "+storyID, e);
			} catch (URISyntaxException e) {
				logger.error(e.getClass().getCanonicalName(), e);
			} finally {
				NDC.pop();
			}
		}
		return taskList;
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
	 * @param joiner TODO
	 * @param tokens
	 * 
	 * @return a properly escaped string for use as an HTTP query string.
	 * @throws EncoderException
	 */
	private String buildQueryString(String... tokens)
		throws EncoderException
	{
		if (tokens.length < 3 || tokens.length%3 != 0) {
			throw new InvalidParameterException("tokens is a list of triples");
		}

		String separator = " AND (";
		StringBuilder builder = new StringBuilder();
		String querySegments[] = new String[tokens.length / 3];
		int index = 0;
		while (index < tokens.length) {
			querySegments[index/3] = String.format("%s %s \"%s\"",
					tokens[index], tokens[index+1], tokens[index+2]);
			index += 3;
			builder.append("(");
		}

		index = 0;
		builder.append(querySegments[index++]).append(")");
		while (index < querySegments.length) {
			builder.append(separator)
			       .append(querySegments[index++])
			       .append("))");
		}
		if (logger.isDebugEnabled()) logger.debug("query="+builder.toString());
		URLCodec codec = new URLCodec("US-ASCII");
		return "query="+codec.encode(builder.toString());
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
	/* (non-Javadoc)
	 * @see org.apache.http.client.CredentialsProvider#clear()
	 */
	@Override
	public void clear() {
		this.userName = "";
		this.password = "";
	}

	/* (non-Javadoc)
	 * @see org.apache.http.client.CredentialsProvider#getCredentials(org.apache.http.auth.AuthScope)
	 */
	@Override
	public Credentials getCredentials(AuthScope scope) {
		return new UsernamePasswordCredentials(this.userName, this.password);
	}

	/* (non-Javadoc)
	 * @see org.apache.http.client.CredentialsProvider#setCredentials(org.apache.http.auth.AuthScope, org.apache.http.auth.Credentials)
	 */
	@Override
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

	@Override
	public <T> T retrieveURI(Class<T> klass, URI uri)
		throws ClientProtocolException, IOException, UnexpectedResponseException
	{
		JAXBElement<T> jaxbElm = retrieveJAXBElement(klass, uri);
		return jaxbElm.getValue();
	}

	protected <T> JAXBElement<T> retrieveJAXBElement(Class<T> klass, URI uri)
		throws ClientProtocolException, IOException, UnexpectedResponseException
	{
		logger.debug(String.format("retrieving %s from %s", klass.toString(), uri.toString()));
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
					JAXBElement<T> elm = (JAXBElement<T>) responseObj;
					return elm;
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
			String msg = String.format("request for '%s' failed: %d %s",
					uri.toString(), status.getStatusCode(), status.getReasonPhrase());
			throw new ClientProtocolException(msg);
		}
	}

	protected <T,U> U transformResultInto(Class<U> klass, T result)
		throws JAXBException, TransformerException, UnexpectedResponseException
	{
		JAXBResult resultDoc = Utilities.runXSLT(new JAXBResult(this.jaxb),
				"xslt/rally.xsl", logger, new JAXBSource(this.jaxb, result),
				this.xformFactory);
		Object resultObj = resultDoc.getResult();
		String resultType = resultObj.getClass().toString();
		if (resultObj instanceof JAXBElement<?>) {
			JAXBElement<?> elm = (JAXBElement<?>) resultObj;
			if (elm.getDeclaredType() == klass) {
				@SuppressWarnings("unchecked")
				U outputObj = (U) elm.getValue();
				return outputObj;
			}
			resultType = elm.getDeclaredType().toString();
		}
		throw Utilities.generateException(UnexpectedResponseException.class,
				"unexpected response type", "expected", klass.toString(),
				"got",resultType);
	}

	protected Description fixDescription(ArtifactType artifact) {
		String descString = artifact.getDescription();
		descString = ltPattern.matcher(descString).replaceAll("<");		// &lt; -> "<"
		descString = gtPattern.matcher(descString).replaceAll(">");		// &gt; -> ">"
		descString = ampPattern.matcher(descString).replaceAll("&");	// necessary to catch &nbsp;
		descString = nbspPattern.matcher(descString).replaceAll(" ");	// &nbsp; -> " "
		descString = brPattern.matcher(descString).replaceAll("<br/>");	// <br> -> <br/>
		descString = ampPattern2.matcher(descString).replaceAll("&amp;"); // & -> &amp;
		if (!descString.startsWith("<p>")) {
			descString = String.format("<p>%s</p>", descString);
		}
		descString = String.format("<description>%s</description>", descString);

		try {
			Object obj = unmarshaller.unmarshal(new StringReader(descString));
			if (obj instanceof Description) {
				return (Description) obj;
			}
		} catch (JAXBException e) {
			logger.error("failed to unmarshal description <<"+descString+">>", e);
		}
		return this.standupFactory.createDescription();
	}

}
