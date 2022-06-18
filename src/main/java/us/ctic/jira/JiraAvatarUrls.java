package us.ctic.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@SuppressWarnings("unused") // Fields are being set by Jackson
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraAvatarUrls
{
    @JsonProperty("16x16")
    public String url16x16;
    @JsonProperty("24x24")
    public String url24x24;
    @JsonProperty("32x32")
    public String url32x32;
    @JsonProperty("48x48")
    public String url48x48;

    public String get16x16()
    {
        return url16x16;
    }

    public String get24x24()
    {
        return url24x24;
    }

    public String get32x32()
    {
        return url32x32;
    }

    public String get48x48()
    {
        return url48x48;
    }

    @Override
    public String toString()
    {
        return "JiraAvatarUrls{" +
               "_16x16='" + url16x16 + '\'' +
               ", _24x24='" + url24x24 + '\'' +
               ", _32x32='" + url32x32 + '\'' +
               ", _48x48='" + url48x48 + '\'' +
               '}';
    }
}
