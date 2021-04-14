package us.ctic.jira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.AbstractResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

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

    private final String host;
    private final BasicHeader authorizationHeader;

    public JiraService(String host, String username, String password)
    {
        this.host = host;

        String userPass = username + ":" + password;
        String encodedCredentials = Base64.getEncoder().encodeToString(userPass.getBytes());
        authorizationHeader = new BasicHeader("Authorization", "Basic " + encodedCredentials);

        // Perform a test connection to the server
        JiraServerInfo serverInfo = getServerInfo();
        logger.info("Connected to {} (version: {})", serverInfo.getServerTitle(), serverInfo.getVersion());
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

    /**
     * Attempts to find the user using the provided username.
     *
     * @param username The username for which to search
     * @return The User or null if not found.
     * @see <a href="https://docs.atlassian.com/software/jira/docs/api/REST/8.13.5/#api/2/user-findUsers">GET /rest/api/2/user/search</a>
     */
    public JiraUser findUserByUsername(String username)
    {
        return findUser(username, null, null, null);
    }

    /**
     * TODO
     *
     * @return The User or null if not found.
     * @see <a href="https://docs.atlassian.com/software/jira/docs/api/REST/8.13.5/#api/2/user-findUsers">GET /rest/api/2/user/search</a>
     */
    public JiraUser findUser(String username, String email, String lastName, String firstName)
    {
        try (CloseableHttpClient httpClient = HttpClients.createDefault())
        {
            URIBuilder uriBuilder = getUriBuilderWithHost().setPath(USER_SEARCH_PATH);
            List<JiraUser> users = null;

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
                    // for a user name of "john.doe", it may match another user named "john.f.doe" if his email was "john.doe@mail.mil.".
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

                // Email should be unique; we should either find exactly one user or none.
                if (users.size() == 1)
                {
                    return users.get(0);
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
                        List<JiraUser> usersWithBothNames = new ArrayList<>();
                        for (JiraUser user : users)
                        {
                            String displayName = user.getDisplayName();
                            if (displayName.contains(firstName))
                            {
                                usersWithBothNames.add(user);
                            }
                        }

                        if (!usersWithBothNames.isEmpty())
                        {
                            JiraUser firstUser = usersWithBothNames.get(0);
                            if (usersWithBothNames.size() > 1)
                            {
                                // TODO: this isn't perfect, but not sure what else to do...
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
            logger.error("Error connecting to Jira for {}", host, e);
        }

        return null;
    }

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

    private URIBuilder getUriBuilderWithHost()
    {
        return new URIBuilder()
                .setScheme("http")
                .setHost(host);
    }

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
}
