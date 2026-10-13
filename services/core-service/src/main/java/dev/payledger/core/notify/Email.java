package dev.payledger.core.notify;

import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * One email, built from short blocks so every message looks the same and always has a plain-text twin
 * (some mail apps and screen readers only show that). Text given here is escaped; nothing is trusted as HTML.
 */
public final class Email {

    private final String subject;
    private final List<String> html = new ArrayList<>();
    private final List<String> text = new ArrayList<>();

    private Email(String subject) {
        this.subject = subject;
    }

    public static Email titled(String subject) {
        return new Email(subject);
    }

    public Email heading(String s) {
        html.add("<h1 style=\"font-size:20px;margin:0 0 12px\">" + esc(s) + "</h1>");
        text.add(s.toUpperCase() + "\n");
        return this;
    }

    public Email para(String s) {
        html.add("<p style=\"margin:0 0 12px;line-height:1.5\">" + esc(s) + "</p>");
        text.add(s + "\n");
        return this;
    }

    /** A list of lines, like "Rent: ₹25,000 on 5 Nov". */
    public Email lines(List<String> items) {
        StringBuilder sb = new StringBuilder("<ul style=\"margin:0 0 12px;padding-left:20px;line-height:1.6\">");
        items.forEach(i -> sb.append("<li>").append(esc(i)).append("</li>"));
        html.add(sb.append("</ul>").toString());
        items.forEach(i -> text.add("- " + i));
        text.add("");
        return this;
    }

    public Email button(String label, String url) {
        html.add("<p style=\"margin:20px 0\"><a href=\"" + esc(url) + "\" style=\"background:#111;color:#fff;padding:10px 18px;"
                + "border-radius:8px;text-decoration:none;display:inline-block\">" + esc(label) + "</a></p>");
        text.add(label + ": " + url + "\n");
        return this;
    }

    /** Why they got it and where to change that; every email ends with one. */
    public Email footer(String why, String settingsUrl) {
        html.add("<hr style=\"border:none;border-top:1px solid #ddd;margin:24px 0 12px\"><p style=\"color:#777;font-size:12px;line-height:1.5\">"
                + esc(why) + (settingsUrl == null ? "" : " <a href=\"" + esc(settingsUrl) + "\" style=\"color:#777\">Email settings</a>") + "</p>");
        text.add("--\n" + why + (settingsUrl == null ? "" : "\nEmail settings: " + settingsUrl));
        return this;
    }

    public String subject() {
        return subject;
    }

    public String html() {
        return "<!doctype html><html><body style=\"margin:0;background:#f6f6f4\"><div style=\"max-width:560px;margin:0 auto;padding:24px;"
                + "font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#111\"><div style=\"font-weight:600;margin-bottom:16px\">PayLedger</div>"
                + "<div style=\"background:#fff;border:1px solid #e5e5e2;border-radius:12px;padding:24px\">" + String.join("", html) + "</div></div></body></html>";
    }

    public String text() {
        return String.join("\n", text);
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s);
    }
}
