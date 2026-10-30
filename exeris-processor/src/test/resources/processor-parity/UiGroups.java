package parity;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.UIGroup;

@ExerisDomain(module = "m", path = "/p")
public class UiGroups {
    @UIGroup(name = "identity", label = "Identity", order = 2, icon = "person")
    private String email;
    @UIGroup(name = "media", description = "Uploads", order = 0, collapsible = false)
    private String avatar;
    @UIGroup(name = "identity", label = "Ignored", collapsed = true, columns = 3, cssClass = "x")
    private String displayName;
    @UIGroup(name = "media", label = "Media", visibleWhen = "premium")
    private String banner;
    @UIGroup(name = "other")
    private String note;
}
