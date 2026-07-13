package ceui.lisa.models;

import java.io.Serializable;

/**
 * user/detail v2 的 profile.badge:形如 {"type":"premium","url":null}。
 * type 目前见过 "premium";url 多为 null(有值时为徽章图 URL)。
 */
public class BadgeBean implements Serializable {

    private String type;
    private String url;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
