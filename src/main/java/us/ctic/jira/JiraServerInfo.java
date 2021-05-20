package us.ctic.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@SuppressWarnings("unused") // Fields are being set by Jackson
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraServerInfo
{
    private String version;
    private String serverTitle;

    public String getVersion()
    {
        return version;
    }

    public String getServerTitle()
    {
        return serverTitle;
    }
}
