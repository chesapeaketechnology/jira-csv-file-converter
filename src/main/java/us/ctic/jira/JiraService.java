package us.ctic.jira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.AbstractResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for interacting with the necessary Jira REST APIs.
 *
 * @see <a href="https://docs.atlassian.com/software/jira/docs/api/REST/8.13.5/">Jira REST API</a>
 * @see <a href="https://developer.atlassian.com/server/jira/platform/basic-authentication/">Jira basic authentication</a>
 */
public class JiraService
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final static String REST_PATH = "/rest/api/2";
    private final static String SERVER_INFO_PATH = REST_PATH + "/serverInfo";
    private final static String USER_SEARCH_PATH = REST_PATH + "/user/search";
    private final static String ISSUE_TYPE_PATH = REST_PATH + "/issue/createmeta";
    private final static String PROJECT_KEY_TOKEN = "projectKeys";
    private final static String EXPAND_TOKEN = "expand";
    private final static String EXPAND_VALUE = "issuetypeNames";
    private final String host;
    private final String projectKey;
    private final BasicHeader authorizationHeader;

    public JiraService(String host, String username, String password, String token)
    {
        this(host, username, password, token, null);
    }

    public JiraService(String host, String username, String password, String token, String projectKey)
    {
        this.host = host;
        this.projectKey = projectKey;

        String userPass = username + ":" + password;
        String encodedCredentials = "Basic " + Base64.getEncoder().encodeToString(userPass.getBytes());
        if(token != null && !token.isEmpty())
        {
            encodedCredentials = "Bearer " + token;
        }
        authorizationHeader = new BasicHeader("Authorization", encodedCredentials);

        // Perform a test connection to the server
        JiraServerInfo serverInfo = getServerInfo();
        logger.info("Connected to {} (version: {})", serverInfo.getServerTitle(), serverInfo.getVersion());

        OBJECT_MAPPER.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
    }

    /**
     * Queries the server info.
     *
     * @return The server info or null if an error occurred.
     * @see <a href="https://docs.atlassian.com/software/jira/docs/api/REST/8.13.5/#api/2/serverInfo">GET /rest/api/2/serverInfo</a>
     */
    public JiraServerInfo getServerInfo()
    {
        try (CloseableHttpClient httpClient = HttpClients.createDefault())
        {
            URI uri = getUriBuilderWithHost().setPath(SERVER_INFO_PATH).build();
            HttpGet httpGet = new HttpGet(uri);
            httpGet.addHeader(authorizationHeader);

            return httpClient.execute(httpGet, new ServerInfoResponseHandler());
        } catch (IOException | URISyntaxException e)
        {
            logger.error("Error connecting to Jira for {}", host, e);
        }

        return null;
    }

    private URIBuilder getUriBuilderWithHost()
    {
        return new URIBuilder()
                .setScheme("http")
                .setHost(host);
    }

    private CloseableHttpClient getCloseableHttpClient() throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException
    {
        //TODO we may need this
        TrustStrategy acceptingTrustStrategy = (chain, authType) -> true;
        SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();
        SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);

        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("https", sslConnectionSocketFactory)
                .register("http", new PlainConnectionSocketFactory())
                .build();

        BasicHttpClientConnectionManager connectionManager = new BasicHttpClientConnectionManager(socketFactoryRegistry);
        return HttpClients.custom()
                .setSSLSocketFactory(sslConnectionSocketFactory)
                .setConnectionManager(connectionManager)
                .build();
    }

    /**
     * Attempts to find the user using the provided username.
     *
     * @param username The username for which to search
     * @return The User or null if not found.
     */
    public JiraUser findUserByUsername(String username)
    {
        return findUser(username, null, null, null);
    }

    /**
     * Attempts to get all the issue type names from the JIRA server.
     *
     * @return a set of all the type names.
     */
    public Set<String> getIssueTypeNames()
    {
        try (CloseableHttpClient httpClient = HttpClients.createDefault())
        {
            URI uri = getUriBuilderWithHost()
                    .setPath(ISSUE_TYPE_PATH)
                    .setParameter(PROJECT_KEY_TOKEN, projectKey)
                    .setParameter(EXPAND_TOKEN, EXPAND_VALUE)
                    .build();
            HttpGet httpGet = new HttpGet(uri);
            httpGet.addHeader(authorizationHeader);

            return httpClient.execute(httpGet, new IssueTypeHandler());
        }
        catch (IOException | URISyntaxException e)
        {
            logger.error("Error connecting to Jira for issue types with {}", host, e);
        }
        return Collections.emptySet();
    }

    /**
     * Attempts to find the user using the provided username, email, last name, and first name, in that order.
     *
     * @param username  The username for which to search
     * @param email     The email for the user
     * @param firstName The first name for the user
     * @param lastName  The last name for the user
     * @return The User or null if not found.
     * @see <a href="https://docs.atlassian.com/software/jira/docs/api/REST/8.13.5/#api/2/user-findUsers">GET /rest/api/2/user/search</a>
     */
    public JiraUser findUser(String username, String email, String firstName, String lastName)
    {
        try (CloseableHttpClient httpClient = HttpClients.createDefault())
        {
            URIBuilder uriBuilder = getUriBuilderWithHost().setPath(USER_SEARCH_PATH);
            List<JiraUser> users;

            if (username != null)
            {
                users = findUsers(httpClient, uriBuilder, username);
                if (!users.isEmpty())
                {
                    if (users.size() == 1)
                    {
                        return users.get(0);
                    }

                    // It's possible to find multiple matches since the query isn't applied to just the username. For example,
                    // for a username of "john.doe", it may match another user named "john.f.doe" if his email was "john.doe@gmail.com".
                    // If that happens, return the one with the right username.
                    for (JiraUser user : users)
                    {
                        if (user.getName().equals(username)) return user;
                    }
                }
            }

            if (email != null)
            {
                users = findUsers(httpClient, uriBuilder, email);

                if (users.size() == 1)
                {
                    return users.get(0);
                }

                // Email should be unique, but there may be more than one user for an email address if a service account
                // reuses a user's email. Let's use the last name or first name to narrow it down.
                String name = lastName == null ? firstName : lastName;
                List<JiraUser> usersWithName = filterUsersWithName(name, users);

                if (!usersWithName.isEmpty())
                {
                    JiraUser firstUser = usersWithName.get(0);
                    if (usersWithName.size() > 1)
                    {
                        logger.warn("Multiple users found with the email `{}`; returning the first one: {}",
                                email, firstUser);
                    }

                    return firstUser;
                }
            }

            // If we get to this point, let's search by last name and then try to refine by first name (if provided).
            // The REST API only supports substring matching, and we don't want to just concatenate the first name and
            // last name for several reasons:
            //  - the display name could be in several different formats (e.g. "first last" vs. "last, first")
            //  - the user may have a middle initial included in the display name (e.g. "first MI last")
            if (lastName != null || firstName != null)
            {
                String initialSearchName = lastName == null ? firstName : lastName;
                users = findUsers(httpClient, uriBuilder, initialSearchName);

                if (!users.isEmpty())
                {
                    // Maybe we got lucky and only got one result...
                    if (users.size() == 1)
                    {
                        return users.get(0);
                    }

                    // ...but probably not, so refine with the first name (if both were provided)
                    if (lastName != null && firstName != null)
                    {
                        List<JiraUser> usersWithBothNames = filterUsersWithName(firstName, users);

                        if (!usersWithBothNames.isEmpty())
                        {
                            JiraUser firstUser = usersWithBothNames.get(0);
                            if (usersWithBothNames.size() > 1)
                            {
                                // This isn't perfect, but not sure what else to do... hopefully the username mapping
                                // file will be double checked before the final CSV conversion.
                                logger.warn("Multiple users found with the name `{} {}`; returning the first one: {}",
                                        firstName, lastName, firstUser);
                            }

                            return firstUser;
                        }
                    }
                }

                logger.debug("No users found for {}, {}, {}, {}", username, email, lastName, firstName);
            }
        } catch (IOException | URISyntaxException e)
        {
            logger.error("Error connecting to Jira for user names with {}", host, e);
        }

        return null;
    }

    /**
     * Queries the Jira instance to find users matching the query string.
     *
     * @param httpClient  The HTTP client for the request
     * @param uriBuilder  A URI builder for constructing the URI for the request
     * @param queryString The query string for matching users
     * @return The list of returned users matching the query string
     * @throws URISyntaxException If the URI is invalid
     * @throws IOException        If a problem occurred when making the request
     */
    private List<JiraUser> findUsers(CloseableHttpClient httpClient, URIBuilder uriBuilder, String queryString)
            throws URISyntaxException, IOException
    {
        URI uri = uriBuilder.clearParameters() // In case we have already used this builder
                .addParameter("username", queryString) // This parameter isn't named well; it's used for username, name, and email
                .addParameter("includeInactive", "true")
                .build();
        HttpGet httpGet = new HttpGet(uri);
        httpGet.addHeader(authorizationHeader);

        List<JiraUser> users = httpClient.execute(httpGet, new UserSearchResponseHandler());

        logger.debug("Found {} users using `{}`", users.size(), queryString);

        return users;
    }

    /**
     * Searches for the users that have the provided name as part of their display name.
     *
     * @param name  The name
     * @param users The users to search
     * @return The list of users that have the provided name in their display name.
     */
    private List<JiraUser> filterUsersWithName(String name, List<JiraUser> users)
    {
        List<JiraUser> usersWithName = new ArrayList<>();
        for (JiraUser user : users)
        {
            String displayName = user.getDisplayName();
            if (displayName.contains(name))
            {
                usersWithName.add(user);
            }
        }
        return usersWithName;
    }

    @Override
    public String toString()
    {
        return host;
    }

    /**
     * The response handler for a server info request.
     */
    private static class ServerInfoResponseHandler extends AbstractResponseHandler<JiraServerInfo>
    {
        @Override
        public JiraServerInfo handleEntity(HttpEntity entity) throws IOException
        {
            String jsonString = EntityUtils.toString(entity);
            return OBJECT_MAPPER.readValue(jsonString, JiraServerInfo.class);
        }
    }

    /**
     * The response handler for a user research request.
     */
    private static class UserSearchResponseHandler extends AbstractResponseHandler<List<JiraUser>>
    {
        @Override
        public List<JiraUser> handleEntity(HttpEntity entity) throws IOException
        {
            String jsonString = EntityUtils.toString(entity);
            List<JiraUser> userList = OBJECT_MAPPER.readValue(jsonString, new UserListTypeReference());

            return userList == null ? Collections.emptyList() : userList;
        }
    }

    /**
     * Type reference for Jackson to allow mapping the JSON array to a list of users.
     */
    private static class UserListTypeReference extends TypeReference<List<JiraUser>>
    {
    }

    /**
     * Gets the issue types from JIRA for a single project or all
     */
    private static class IssueTypeHandler extends AbstractResponseHandler<Set<String>>
    {

        @Override
        public Set<String> handleEntity(HttpEntity entity) throws IOException
        {
            try
            {
                String jsonString = EntityUtils.toString(entity);
                JiraExpand jiraExpand = OBJECT_MAPPER.readValue(jsonString, new ExpandReference());
                if(jiraExpand == null)
                {
                    return Collections.emptySet();
                }
                return jiraExpand.getProjects().stream()
                        .map(JiraProject::getIssueTypes)
                        .flatMap(Collection::stream)
                        .map(JiraIssueType::getName)
                        .collect(Collectors.toSet());
            }
            catch (Exception e)
            {
                logger.error("Could not parse project data: ", e);
            }
            return Collections.emptySet();
        }
    }

    private static class ExpandReference extends TypeReference<JiraExpand>
    {
    }
}
