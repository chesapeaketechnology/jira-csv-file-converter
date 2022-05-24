package us.ctic.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@SuppressWarnings("unused") // Fields are being set by Jackson
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraAvatarUrls
{
    @JsonProperty("16x16")
    public String _16x16;
    @JsonProperty("24x24")
    public String _24x24;
    @JsonProperty("32x32")
    public String _32x32;
    @JsonProperty("48x48")
    public String _48x48;

    public String get_16x16()
    {
        return _16x16;
    }

    public String get_24x24()
    {
        return _24x24;
    }

    public String get_32x32()
    {
        return _32x32;
    }

    public String get_48x48()
    {
        return _48x48;
    }

    @Override
    public String toString()
    {
        return "JiraAvatarUrls{" +
                "_16x16='" + _16x16 + '\'' +
                ", _24x24='" + _24x24 + '\'' +
                ", _32x32='" + _32x32 + '\'' +
                ", _48x48='" + _48x48 + '\'' +
                '}';
    }
}
