package standup.connector.rally;

import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
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

import org.apache.http.client.ClientProtocolException;
import org.apache.log4j.Logger;
import org.apache.log4j.NDC;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rallydev.rest.RallyRestApi;
import com.rallydev.rest.request.QueryRequest;
import com.rallydev.rest.response.QueryResponse;
import com.rallydev.rest.util.Fetch;
import com.rallydev.rest.util.QueryFilter;

import standup.connector.ConnectorException;
import standup.connector.DefaultHttpClientFactory;
import standup.connector.HttpClientFactory;
import standup.connector.UnexpectedResponseException;
import standup.utility.Utilities;
import standup.xml.Description;
import standup.xml.Links;
import standup.xml.Links.Link;
import standup.xml.ObjectFactory;
import standup.xml.StoryList;
import standup.xml.StoryType;
import standup.xml.TaskList;
import standup.xml.TaskType;
import standup.xml.TopLevelObject;



/**
 * A connection to the Rally Server.
 * <p>
 * The connection maintains the authorization information for the
 * session along with the working set of HTTP headers.
 */
public class ServerConnection
	implements standup.connector.ServerConnection,
	           Serializable
{
	private static final long serialVersionUID = -4302496608447788915L;
	private static final Logger logger = Logger.getLogger(ServerConnection.class);

	static final String RALLY_SERVER_URL= "https://rally1.rallydev.com";
	static final String RALLY_QUERY_REL = "Rally Query";
	static final String RALLY_PARENT_URL_REL = "Parent URL";
	static final String RALLY_OBJECT_URL_REL = "Object URL";


	private static final Pattern ltPattern = Pattern.compile("&lt;");
	private static final Pattern gtPattern = Pattern.compile("&gt;");
	private static final Pattern ampPattern = Pattern.compile("&amp;");
	private static final Pattern nbspPattern = Pattern.compile("&nbsp;");
	private static final Pattern brPattern = Pattern.compile("<br\\s*>");
	private static final Pattern ampPattern2 = Pattern.compile("&");
	private static final Pattern quotPattern = Pattern.compile("\"");
	
	private ObjectFactory objFactory = new ObjectFactory();
	private JAXBContext jaxb;
	private Unmarshaller unmarshaller;
	
	private String username;
	private String password;
	
	public ServerConnection() {
		try {
			this.jaxb = JAXBContext.newInstance("standup.xml");
			this.unmarshaller = jaxb.createUnmarshaller();
		} catch (JAXBException e) {
			throw new Error("failed to initialize XML bindings", e);
		}
	}
	
	@Override
	public List<IterationStatus> listIterationsForProject(String projectName)
			throws IOException, ConnectorException, URISyntaxException {
		List<IterationStatus> iterationList = new ArrayList<IterationStatus>();
		RallyRestApi restApi = new RallyRestApi(new URI(RALLY_SERVER_URL), username, password);

		QueryRequest query = new QueryRequest("Iterations");

		query.setFetch(new Fetch("Name"));
		query.setQueryFilter(new QueryFilter("Project.Name", "=", "Adrenalin SeaDAC Renderer"));
		
		QueryResponse resp = restApi.query(query);
		
		for(JsonElement r : resp.getResults()) {
			JsonObject result = r.getAsJsonObject();
			iterationList.add(new IterationStatus(result.get("Name").getAsString(), new URI(result.get("_ref").getAsString())));
		}
		
		restApi.close();
		return iterationList;
	}
	@Override
	public List<IterationStatus> listIterationsInvolvingUser(String userName)
			throws IOException, ConnectorException, URISyntaxException {
	
		List<IterationStatus> iterationList = new ArrayList<IterationStatus>();
		RallyRestApi restApi = new RallyRestApi(new URI(RALLY_SERVER_URL), username, password);

		QueryRequest query = new QueryRequest("Iterations");

		query.setFetch(new Fetch("Name"));
		query.setQueryFilter(new QueryFilter("UserIterationCapacities.User.Name", "=", userName));
		
		QueryResponse resp = restApi.query(query);
		
		for(JsonElement r : resp.getResults()) {
			JsonObject result = r.getAsJsonObject();
			iterationList.add(new IterationStatus(result.get("Name").getAsString(), new URI(result.get("_ref").getAsString())));
		}
		
		restApi.close();
		return iterationList;
		
	}
	@Override
	public StoryList retrieveStoriesForIteration(String iterationName)
			throws IOException,  ConnectorException,
			TransformerException, URISyntaxException {
		QueryFilter filter = new QueryFilter("Iteration.Name", "=", iterationName);
		return this.retrieveStoriesByQuery(filter);
	}
	@Override
	public StoryList retrieveStoriesForProjectIteration(String project,
			String iterationName) throws IOException, ClientProtocolException, TransformerException, ConnectorException, URISyntaxException {
		QueryFilter filter = QueryFilter.and(new QueryFilter("Project.Name", "=", project),
											 new QueryFilter("Iteration.Name", "=", iterationName));
		return this.retrieveStoriesByQuery(filter);
	}
	@Override
	public StoryList retrieveStories(String[] stories) throws IOException,
			ClientProtocolException, ConnectorException, TransformerException, URISyntaxException {
		if(stories.length == 0) {
			return objFactory.createStoryList();
		}
		QueryFilter filter = new QueryFilter("FormattedID", "=", stories[0]);
		for(int i = 1; i < stories.length; i++) {
			filter = filter.or(new QueryFilter("FormattedID", "=", stories[0]));
		}
	
		return this.retrieveStoriesByQuery(filter);
	}
	@Override
	public TaskList retrieveTasks(StoryList stories) throws IOException,
			ClientProtocolException, ConnectorException, TransformerException, URISyntaxException {
		RallyRestApi restApi = new RallyRestApi(new URI(RALLY_SERVER_URL), username, password);
		QueryRequest taskQuery = new QueryRequest("Task");
	
		TaskList taskList = objFactory.createTaskList();
		try {
			for (StoryType story: stories.getStory()) {
				String storyID = story.getIdentifier();
				Link storyLink = findLinkByRel(story, RALLY_OBJECT_URL_REL);
				if (storyLink != null) {
					storyLink.setRel(RALLY_PARENT_URL_REL);
				}
			
				NDC.push("retrieving tasks for "+ storyID);
				logger.debug(NDC.peek());
				QueryFilter filter = new QueryFilter("WorkProduct.FormattedID", "=", storyID);
				taskQuery.setQueryFilter(filter);
				QueryResponse query = restApi.query(taskQuery);
				if(query.wasSuccessful()) {
					for(JsonElement e : query.getResults()) {
						if(e == null)
							continue;
						TaskType task = objFactory.createTaskType();
						JsonObject jsonTask = e.getAsJsonObject();
						String taskName = jsonTask.get("Name").getAsString();
						
						task.setParentIdentifier(storyID);
						task.setDescription(fixDescription(getValueOrDefault(jsonTask.get("Description"), "")));
						if(!jsonTask.get("Owner").isJsonNull()) {
							task.setOwner(getValueOrDefault(jsonTask.get("Owner").getAsJsonObject().get("_refObjectName"), ""));
						}
						task.setFullName(taskName);
						task.setShortName((taskName.length() > 30)? taskName.substring(0, 30) : taskName);
						task.setIdentifier(jsonTask.get("FormattedID").getAsString());
						task.setDetailedEstimate(getValueOrDefault(jsonTask.get("Estimate"), new Double(0.0)));
						task.setTodoRemaining(getValueOrDefault(jsonTask.get("Estimate"), new Double(0.0)));
						task.setEffortApplied(getValueOrDefault(jsonTask.get("Actuals"), new Double(0.0)));
						task.setDescription(fixDescription(getValueOrDefault(jsonTask.get("Description"), "")));
						addLink(story, jsonTask.get("_ref").getAsString(), RALLY_OBJECT_URL_REL);
						
						addLink(task, jsonTask.get("_ref").getAsString(), RALLY_OBJECT_URL_REL);
						addLink(task, storyLink);
						taskList.getTask().add(task);
					}
				}
			}
		}  finally {
			NDC.pop();
			restApi.close();
		}
		return taskList;
	}
	
	
	private StoryList retrieveStoriesByQuery(QueryFilter filter)
			throws IOException,  ConnectorException,
			TransformerException, URISyntaxException {
		RallyRestApi restApi = new RallyRestApi(new URI(RALLY_SERVER_URL), username, password);

		QueryRequest storyQuery = new QueryRequest("HierarchicalRequirement");
		QueryRequest defectQuery = new QueryRequest("Defect");

		StoryList stories = objFactory.createStoryList();
		
		try {
			defectQuery.setQueryFilter(filter);
			storyQuery.setQueryFilter(filter);
			
			QueryResponse storyResp = restApi.query(storyQuery);
			QueryResponse defectResp = restApi.query(defectQuery);
			
			if(storyResp.wasSuccessful()) {
				List<StoryType> storyList = getStoryList(storyResp.getResults());
				stories.getStory().addAll(storyList);
			}
			
			if(defectResp.wasSuccessful()) {
				List<StoryType> defectList = getStoryList(defectResp.getResults());
				stories.getStory().addAll(defectList);
			}
		} finally {
			restApi.close();
		}
		return stories;
	}	
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	public String getPassword() {
		return password;
	}
	public void setPassword(String password) {
		this.password = password;
	}


	private List<StoryType> getStoryList(JsonArray jsonStories) {
		List<StoryType> storyList = new ArrayList<StoryType>();

		for(JsonElement e : jsonStories) {
			if(e == null)
				continue;
			JsonObject jsonStory = e.getAsJsonObject();
			StoryType story = objFactory.createStoryType();
			String storyName = jsonStory.get("Name").getAsString();
			if(!jsonStory.get("Owner").isJsonNull()) {
				story.setOwner(getValueOrDefault(jsonStory.get("Owner").getAsJsonObject().get("_refObjectName"), ""));
			}
			story.setFullName(storyName);
			story.setShortName((storyName.length() > 30)? storyName.substring(0, 30) : storyName);
			story.setIdentifier(jsonStory.get("FormattedID").getAsString());
			story.setEstimate(getValueOrDefault(jsonStory.get("PlanEstimate"), new Double(0.0)));
			story.setDescription(fixDescription(getValueOrDefault(jsonStory.get("Description"), "")));
			addLink(story, jsonStory.get("_ref").getAsString(), RALLY_OBJECT_URL_REL);
			storyList.add(story);
			
			logger.info(String.format("%s - %s", jsonStory.get("FormattedID").getAsString(), storyName));
		}
		
		return storyList;
	}

	@SuppressWarnings("unchecked")
	private <T> T getValueOrDefault(JsonElement obj, T value) {
		if (!obj.isJsonNull()) {
			if(value instanceof String) {
				return (T) obj.getAsString();
			} else if (value instanceof Double) {
				return (T) new Double(obj.getAsDouble());
			}
		}
		return value;
	}
	
	Description fixDescription(String descString) {
		descString = ltPattern.matcher(descString).replaceAll("<");		// &lt; -> "<"
		descString = gtPattern.matcher(descString).replaceAll(">");		// &gt; -> ">"
		descString = ampPattern.matcher(descString).replaceAll("&");	// necessary to catch &nbsp;
		descString = nbspPattern.matcher(descString).replaceAll(" ");	// &nbsp; -> " "
		descString = brPattern.matcher(descString).replaceAll("<br/>");	// <br> -> <br/>
		descString = quotPattern.matcher(descString).replaceAll("\"");	// &quot; -> "
		descString = ampPattern2.matcher(descString).replaceAll("&amp;"); // & -> &amp;
		
		descString = String.format("<description>%s</description>", descString);

		try {
			Object obj = unmarshaller.unmarshal(new StringReader(descString));
			if (obj instanceof Description) {
				return (Description) obj;
			}
		} catch (JAXBException e) {
			logger.error("failed to unmarshal description <<"+descString+">>", e);
		}
		return objFactory.createDescription();
		
	}

	private void addLink(TopLevelObject obj, String linkURI, String linkRel) {
		Link l = objFactory.createLinksLink();
		l.setOwner(this.getClass().getCanonicalName());
		l.setValue(linkURI);
		l.setRel(linkRel);
		addLink(obj, l);
	}

	private void addLink(TopLevelObject obj, Link l) {
		if (l != null) {
			if (obj.getLinks() == null) {
				obj.setLinks(objFactory.createLinks());
			}
			obj.getLinks().getLink().add(l);
		}
	}

	private Link findLinkByRel(TopLevelObject obj, String linkRel) {
		Links links = obj.getLinks();
		if (links != null) {
			for (Link l: links.getLink()) {
				if (l.getRel().equalsIgnoreCase(linkRel)) {
					Link cloned = objFactory.createLinksLink();
					cloned.setOwner(l.getOwner());
					cloned.setRel(l.getRel());
					cloned.setValue(l.getValue());
					return cloned;
				}
			}
		}
		return null;
	}
	
}
