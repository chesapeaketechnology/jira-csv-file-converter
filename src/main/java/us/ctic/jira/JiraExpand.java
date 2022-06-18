package us.ctic.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

@SuppressWarnings("unused") // Fields are being set by Jackson
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraExpand
{
    public String expand;
    public List<JiraProject> projects;

    public String getExpand()
    {
        return expand;
    }

    public List<JiraProject> getProjects()
    {
        return Collections.unmodifiableList(projects);
    }

    @Override
    public String toString()
    {
        return "JiraExpand{" +
                "expand='" + expand + '\'' +
                ", projects=" + projects +
                '}';
    }
}
