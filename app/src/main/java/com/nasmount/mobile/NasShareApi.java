package com.nasmount.mobile;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Native File Station share links; credentials are never part of a URL. */
final class NasShareApi {
    static class Failure extends IOException {
        final String reason;
        Failure(String reason) { super(reason); this.reason = reason; }
        Failure(String reason, Throwable cause) { super(reason, cause); this.reason = reason; }
        String userMessage() { return reason; }
    }

    static final class OtpRequired extends Failure {
        final Models.RemoteProfile profile;
        final boolean rejected;
        OtpRequired(Models.RemoteProfile profile, boolean rejected) {
            super(rejected ? "DSM 2단계 인증코드가 올바르지 않습니다(404). 새 코드를 입력하세요." :
                    "DSM 2단계 인증코드가 필요합니다(403).");
            this.profile = profile;
            this.rejected = rejected;
        }
    }

    static String networkMessage(IOException error) {
        if (error instanceof SSLException)
            return "NAS HTTPS 인증서 검증에 실패했습니다. DSM 인증서가 휴대전화에서 신뢰되는지 확인하세요.";
        if (error instanceof UnknownHostException)
            return "NAS 도메인을 찾지 못했습니다. DDNS 주소와 휴대전화의 네트워크를 확인하세요.";
        if (error instanceof SocketTimeoutException)
            return "NAS 응답 시간이 초과됐습니다. 포트와 방화벽, 외부 접속을 확인하세요.";
        if (error instanceof ConnectException)
            return "NAS 관리 포트에 연결할 수 없습니다. HTTPS 포트와 방화벽을 확인하세요.";
        return "NAS 관리 API와 통신하지 못했습니다 (" +
                error.getClass().getSimpleName() + "). 네트워크와 HTTPS 연결을 확인하세요.";
    }

    static String synologyErrorMessage(String api, String method, int code) {
        if ("SYNO.API.Auth".equals(api)) {
            switch (code) {
                case 400: return "Synology DSM 로그인 실패(400). 관리 계정/비밀번호를 확인하세요.";
                case 401: return "Synology 계정이 비활성화되었습니다(401).";
                case 402: return "Synology 계정의 사용 권한이 없습니다(402).";
                case 403: return "DSM 2단계 인증코드가 필요합니다(403).";
                case 404: return "DSM 2단계 인증코드가 올바르지 않습니다(404).";
                case 406: return "DSM 계정에서 2단계 인증 설정을 완료해야 합니다(406).";
                case 407: return "DSM이 현재 IP 접근을 차단했습니다(407). DSM 자동 차단 목록을 확인하세요.";
                default: return "Synology DSM 인증 실패(코드 " + code + "). 계정 보안을 확인하세요.";
            }
        }
        if ("SYNO.FileStation.List".equals(api)) {
            if (code == 119 || code == 105 || code == 106)
                return "File Station 로그인 세션이 유효하지 않습니다(코드 " + code + ").";
            if (code >= 401 && code <= 404)
                return "File Station 폴더 조회 권한이 없습니다(코드 " + code + "). DSM 공유 폴더 권한을 확인하세요.";
            if (code >= 102 && code <= 104)
                return "Synology File Station 목록 API 버전 또는 요청이 거부됐습니다(코드 " + code + ").";
        }
        return "Synology " + api + " / " + method + " 실패(코드 " + code + ").";
    }
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(24, TimeUnit.SECONDS)
            .writeTimeout(24, TimeUnit.SECONDS).build();

    static final class Link {
        final String id, path, url;
        final long expires;
        Link(String id, String path, String url, long expires) {
            this.id = id; this.path = path; this.url = url; this.expires = expires;
        }
    }

    static final class RevokeReport {
        final int requested;
        final int succeeded;
        final List<String> failures;
        RevokeReport(int requested, int succeeded, List<String> failures) {
            this.requested = requested;
            this.succeeded = succeeded;
            this.failures = failures;
        }
        int failed() { return requested - succeeded; }
    }

    interface LinkRevoker { void revoke(Link link) throws Exception; }

    /** Continue revoking other selected links even when one NAS request fails. */
    static RevokeReport revokeEach(List<Link> targets, LinkRevoker revoker) {
        int succeeded = 0;
        List<String> failures = new ArrayList<>();
        for (Link link : targets) {
            try {
                revoker.revoke(link);
                succeeded++;
            } catch (Exception failure) {
                String description = failure.getMessage();
                if (description == null || description.isEmpty())
                    description = "NAS가 링크 해제를 거부했습니다";
                failures.add(link.path + " (" + description + ")");
            }
        }
        return new RevokeReport(targets.size(), succeeded, failures);
    }

    static final class NasEntry {
        final String name, path;
        final boolean directory;
        NasEntry(String name, String path, boolean directory) {
            this.name = name; this.path = path; this.directory = directory;
        }
    }

    private final Models.RemoteProfile profile;
    private final HttpUrl base;
    private final OkHttpClient transport;
    private String synoToken = "";

    NasShareApi(Models.RemoteProfile profile) {
        if (profile == null ||
                !(NasSharePolicy.SYNOLOGY.equals(profile.nasVendor) ||
                  NasSharePolicy.QNAP.equals(profile.nasVendor)) ||
                profile.nasApiBase == null || profile.nasApiBase.isEmpty())
            throw new IllegalArgumentException("Synology/QNAP 관리 서버 설정이 없습니다.");
        this.profile = profile;
        this.base = HttpUrl.get(NasSharePolicy.validateApiBase(profile.nasApiBase) + "/");
        this.transport = "https".equals(base.scheme())
                ? CLIENT.newBuilder().hostnameVerifier(
                    WebDavTlsAliasPolicy.forNasManagement(base.host(), true)).build()
                : CLIENT;
    }

    interface AuthAction<T> { T run(String sid) throws Exception; }

    private <T> T authenticated(AuthAction<T> action) throws Exception {
        String sid = NasSharePolicy.SYNOLOGY.equals(profile.nasVendor) ?
                synologyLogin() : qnapLogin();
        try { return action.run(sid); }
        finally {
            try {
                if (NasSharePolicy.SYNOLOGY.equals(profile.nasVendor))
                    synology("SYNO.API.Auth", 6, "logout", sid, null);
                else {
                    // Modern QTS authenticates with authLogin.cgi; expire the
                    // per-operation SID without retaining it in app storage.
                    FormBody body = new FormBody.Builder().add("logout", "1")
                            .add("sid", sid).build();
                    execute("cgi-bin/authLogout.cgi", body);
                }
            } catch (Exception ignored) {}
        }
    }

    private String execute(String endpoint, FormBody body) throws IOException {
        return execute(endpoint, body, "");
    }

    private String execute(String endpoint, FormBody body, String csrfToken) throws IOException {
        HttpUrl.Builder u = base.newBuilder();
        for (String segment : endpoint.split("/")) u.addPathSegment(segment);
        // DSM may reject SynoToken in form fields; supply it as a query
        // argument, while keeping account, password and OTP in POST body.
        if (csrfToken != null && !csrfToken.isEmpty())
            u.addQueryParameter("SynoToken", csrfToken);
        Request request = new Request.Builder().url(u.build()).post(body)
                .header("Cache-Control", "no-store").build();
        try (Response response = transport.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null)
                throw new Failure("DSM/QTS 관리 API HTTP " + response.code() +
                        " 오류입니다. HTTPS 포트/역방향 프록시/API 권한을 확인하세요.");
            String bodyText = response.body().string();
            if (bodyText.length() > 2_000_000)
                throw new Failure("NAS API 응답 크기가 제한을 초과했습니다.");
            String trimmed = bodyText.trim().toLowerCase(java.util.Locale.ROOT);
            if (trimmed.startsWith("<!doctype") || trimmed.startsWith("<html"))
                throw new Failure("NAS가 API JSON 대신 웹 로그인 화면을 반환했습니다. 관리 URL을 확인하세요.");
            return bodyText;
        } catch (Failure error) {
            throw error;
        } catch (IOException error) {
            throw new Failure(networkMessage(error), error);
        }
    }

    private JSONObject synology(String api, int version, String method,
                                String sid, FormBody.Builder extra) throws Exception {
        FormBody.Builder body = new FormBody.Builder()
                .add("api", api).add("version", String.valueOf(version))
                .add("method", method);
        if (sid != null && !sid.isEmpty()) body.add("_sid", sid);
        if (extra != null) {
            FormBody form = extra.build();
            for (int i = 0; i < form.size(); i++)
                body.add(form.name(i), form.value(i));
        }
        JSONObject result = new JSONObject(execute("webapi/entry.cgi", body.build(),
                sid == null ? "" : synoToken));
        if (!result.optBoolean("success", false)) {
            int error = result.optJSONObject("error") == null ? -1 :
                    result.optJSONObject("error").optInt("code", -1);
            if ("SYNO.API.Auth".equals(api) && "login".equals(method) &&
                    (error == 403 || error == 404))
                throw new OtpRequired(profile, error == 404);
            throw new Failure(synologyErrorMessage(api, method, error));
        }
        return result.optJSONObject("data") == null ? new JSONObject() :
                result.getJSONObject("data");
    }

    private String synologyLogin() throws Exception {
        FormBody.Builder credentials = new FormBody.Builder()
                .add("account", profile.nasApiUser)
                .add("passwd", profile.nasApiPassword)
                .add("session", "FileStation")
                .add("format", "sid")
                .add("enable_syno_token", "yes");
        String otp = profile.nasOtpCode == null ? "" : profile.nasOtpCode.trim();
        if (!otp.isEmpty()) {
            if (!otp.matches("[0-9]{6}"))
                throw new Failure("DSM 인증코드는 숫자 6자리여야 합니다.");
            credentials.add("otp_code", otp);
            if (profile.nasRememberDevice) {
                credentials.add("enable_device_token", "yes");
                credentials.add("device_name", "FileHub AIO Android");
            }
        } else if (profile.nasDeviceId != null && !profile.nasDeviceId.isEmpty()) {
            credentials.add("device_name", "FileHub AIO Android")
                    .add("device_id", profile.nasDeviceId);
        }
        JSONObject data;
        try {
            data = synology("SYNO.API.Auth", 6, "login", null, credentials);
        } catch (OtpRequired required) {
            if (otp.isEmpty()) profile.nasDeviceId = "";
            throw required;
        } finally {
            // OTP is consumed once, never kept for later retries or serialized.
            profile.nasOtpCode = "";
        }
        String sid = data.optString("sid", "");
        synoToken = data.optString("synotoken", data.optString("SynoToken", ""));
        if (!otp.isEmpty() && profile.nasRememberDevice) {
            String did = data.optString("did", "");
            if (!did.isEmpty()) profile.nasDeviceId = did;
        }
        profile.nasRememberDevice = false;
        if (sid.isEmpty()) throw new IOException("Synology DSM 인증 세션을 받지 못했습니다.");
        return sid;
    }

    private static String xmlTag(String xml, String tag) throws IOException {
        Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">",
                Pattern.DOTALL).matcher(xml);
        if (!matcher.find()) throw new IOException("QNAP 인증 응답 형식이 올바르지 않습니다.");
        return matcher.group(1).replace("<![CDATA[", "")
                .replace("]]>", "").trim();
    }

    private String qnapLogin() throws Exception {
        String encoded = Base64.encodeToString(profile.nasApiPassword.getBytes(
                StandardCharsets.UTF_8), Base64.NO_WRAP);
        String result = execute("cgi-bin/authLogin.cgi", new FormBody.Builder()
                .add("user", profile.nasApiUser).add("pwd", encoded).build());
        if (!"1".equals(xmlTag(result, "authPassed")))
            throw new IOException("QNAP QTS 인증에 실패했습니다. 계정 및 2단계 인증 설정을 확인하세요.");
        String sid = xmlTag(result, "authSid");
        if (!sid.matches("[A-Za-z0-9_-]{4,160}"))
            throw new IOException("QNAP 인증 세션 형식이 잘못됐습니다.");
        return sid;
    }

    private JSONObject qnap(String operation, String sid, FormBody.Builder fields)
            throws Exception {
        FormBody.Builder body = new FormBody.Builder()
                .add("func", operation).add("sid", sid);
        if (fields != null) {
            FormBody form = fields.build();
            for (int i = 0; i < form.size(); i++) body.add(form.name(i), form.value(i));
        }
        JSONObject data = new JSONObject(execute("cgi-bin/filemanager/utilRequest.cgi", body.build()));
        if (data.optInt("status", 0) != 1)
            throw new IOException("QNAP File Station 요청 실패 (상태 " +
                    data.optInt("status", -1) + ").");
        return data;
    }

    /** QNAP get_tree returns a JSON array instead of a status object. */
    private String qnapTreeResponse(String sid, String folder) throws Exception {
        return execute("cgi-bin/filemanager/utilRequest.cgi", new FormBody.Builder()
                .add("func", "get_tree").add("sid", sid)
                .add("is_iso", "0").add("node", folder).build());
    }

    static List<NasEntry> parseQnapShares(String raw) throws Exception {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[")) {
            JSONObject error = new JSONObject(trimmed);
            throw new IOException("QNAP 공유 폴더 조회 실패 (상태 " +
                    error.optInt("status", -1) + "). 권한과 File Station 접속을 확인하세요.");
        }
        JSONArray entries = new JSONArray(trimmed);
        List<NasEntry> result = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String name = entry.optString("text", "").trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
            String path;
            try { path = NasSharePolicy.validateShareRoot("/" + name); }
            catch (IllegalArgumentException invalid) { continue; }
            if (path.indexOf('/', 1) >= 0) continue;
            result.add(new NasEntry(name, path, true));
        }
        return result;
    }

    static List<NasEntry> parseQnapFolder(String raw, String parent) throws Exception {
        JSONObject data = new JSONObject(raw);
        if (data.has("status") && data.optInt("status", 1) != 1)
            throw new IOException("QNAP 폴더 목록 조회 실패 (상태 " +
                    data.optInt("status", -1) + ").");
        JSONArray files = data.optJSONArray("datas");
        if (files == null) throw new IOException("QNAP 파일 목록 형식이 올바르지 않습니다.");
        List<NasEntry> entries = new ArrayList<>();
        String normalized = RemoteProvider.relativePath(parent);
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            String name = file.optString("filename", "");
            if (name.isEmpty() || name.equals(".") || name.equals("..") ||
                    name.contains("/") || name.contains("\\")) continue;
            String full = RemoteProvider.relativePath(normalized + "/" + name);
            boolean directory = file.optInt("isfolder", 0) == 1 ||
                    file.optBoolean("isfolder", false);
            entries.add(new NasEntry(name, full, directory));
        }
        return entries;
    }

    void verify() throws Exception {
        authenticated(sid -> {
            if (NasSharePolicy.SYNOLOGY.equals(profile.nasVendor))
                synology("SYNO.FileStation.Sharing", 3, "list", sid,
                        new FormBody.Builder().add("offset", "0").add("limit", "1"));
            else parseQnapShares(qnapTreeResponse(sid, "share_root"));
            return null;
        });
    }

    List<NasEntry> listShares() throws Exception {
        if (NasSharePolicy.QNAP.equals(profile.nasVendor))
            return authenticated(sid -> parseQnapShares(qnapTreeResponse(sid, "share_root")));
        return authenticated(sid -> {
            JSONObject data = synology("SYNO.FileStation.List", 2, "list_share", sid,
                    new FormBody.Builder());
            JSONArray shares = data.optJSONArray("shares");
            if (shares == null)
                throw new IOException("DSM이 공유 폴더 목록을 반환하지 않았습니다.");
            List<NasEntry> result = new ArrayList<>();
            for (int i = 0; i < shares.length(); i++) {
                JSONObject share = shares.getJSONObject(i);
                String path = NasSharePolicy.validateShareRoot(share.optString("path", ""));
                String name = share.optString("name", path.substring(1));
                result.add(new NasEntry(name, path, true));
            }
            return result;
        });
    }

    List<NasEntry> listFolder(String folder) throws Exception {
        String path = RemoteProvider.relativePath(folder);
        if (!NasSharePolicy.inAllowedRoot(profile, path))
            throw new IOException("선택하지 않은 NAS 공유 폴더는 탐색할 수 없습니다.");
        if (NasSharePolicy.QNAP.equals(profile.nasVendor))
            return authenticated(sid -> parseQnapFolder(execute(
                    "cgi-bin/filemanager/utilRequest.cgi", new FormBody.Builder()
                            .add("func", "get_list").add("sid", sid)
                            .add("is_iso", "0").add("list_mode", "all")
                            .add("path", path).add("sort", "filename")
                            .add("dir", "ASC").add("start", "0")
                            .add("limit", "500").add("hidden_file", "0").build()), path));
        return authenticated(sid -> {
            JSONObject data = synology("SYNO.FileStation.List", 2, "list", sid,
                    new FormBody.Builder().add("folder_path", JSONObject.quote(path)));
            JSONArray files = data.optJSONArray("files");
            if (files == null)
                throw new IOException("DSM이 폴더 내용을 반환하지 않았습니다.");
            List<NasEntry> result = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.getJSONObject(i);
                String full = RemoteProvider.relativePath(item.optString("path", ""));
                if (!NasSharePolicy.inAllowedRoot(profile, full)) continue;
                result.add(new NasEntry(item.optString("name", full), full,
                        item.optBoolean("isdir", false)));
            }
            return result;
        });
    }

    Link create(String relativePath, int days, String customDate, String password)
            throws Exception {
        return createFileStationPath(NasSharePolicy.mapPath(profile, relativePath),
                days, customDate, password);
    }

    Link createFileStationPath(String fullPath, int days, String customDate,
                               String password) throws Exception {
        String path = RemoteProvider.relativePath(fullPath);
        if (!NasSharePolicy.inAllowedRoot(profile, path))
            throw new IOException("허용하지 않은 NAS 공유 폴더의 링크는 생성할 수 없습니다.");
        String date = NasSharePolicy.expiryDate(days, customDate);
        if (password != null && password.length() > 16 &&
                NasSharePolicy.SYNOLOGY.equals(profile.nasVendor))
            throw new IllegalArgumentException("Synology 공유 암호는 최대 16자입니다.");
        return authenticated(sid -> {
            if (NasSharePolicy.SYNOLOGY.equals(profile.nasVendor)) {
                FormBody.Builder body = new FormBody.Builder()
                        .add("path", JSONObject.quote(path))
                        .add("date_expired", "0".equals(date) ? "0" : JSONObject.quote(date));
                if (password != null && !password.isEmpty())
                    body.add("password", JSONObject.quote(password));
                JSONObject result = synology("SYNO.FileStation.Sharing", 3, "create", sid, body);
                JSONArray links = result.optJSONArray("links");
                if (links == null || links.length() == 0)
                    throw new IOException("Synology가 공유 링크를 반환하지 않았습니다.");
                JSONObject link = links.getJSONObject(0);
                if (link.optInt("error", 0) != 0)
                    throw new IOException("Synology 공유 링크 생성 실패 (" +
                            link.optInt("error", -1) + ").");
                return checkedLink(link.optString("id"), path, link.optString("url"), 0);
            }
            URI publicHost = new URI(profile.nasPublicHost.isEmpty() ?
                    profile.nasApiBase : profile.nasPublicHost);
            String filename = path.substring(path.lastIndexOf('/') + 1);
            String host = publicHost.getHost();
            int port = publicHost.getPort() > 0 ? publicHost.getPort() : 443;
            FormBody.Builder options = new FormBody.Builder()
                    .add("file_total", "1")
                    .add("file_name", path).add("link_name", filename)
                    .add("hostname", host).add("port", String.valueOf(port))
                    .add("ssl", "true").add("network_type", "internet")
                    .add("download_type", "create_download_link")
                    .add("new_share", "1").add("direct_create", "1")
                    .add("x_option", "0").add("c", "1")
                    .add("option", "1").add("access_enabled",
                            password == null || password.isEmpty() ? "false" : "true")
                    .add("access_code", password == null ? "" : password);
            if (days == 0) options.add("valid_duration", "forever");
            else if (days > 0) options.add("valid_duration", "period_of_time")
                    .add("day", String.valueOf(days)).add("hour", "0");
            else options.add("valid_duration", "specific_time")
                    .add("datetime", date.replace('-', '/') + " 23:59");
            JSONObject data = qnap("get_share_link", sid, options);
            JSONArray links = data.optJSONArray("links");
            String url = links != null && links.length() > 0 ?
                    links.getJSONObject(0).optString("link_url") : data.optString("host", "");
            String sidOfShare = data.optString("ssid", "");
            if (sidOfShare.isEmpty() && links != null && links.length() > 0)
                sidOfShare = links.getJSONObject(0).optString("ssid", "");
            return checkedLink(sidOfShare, path, url,
                    data.optLong("expire_time", 0));
        });
    }

    List<Link> list() throws Exception {
        return authenticated(sid -> {
            List<Link> result = new ArrayList<>();
            if (NasSharePolicy.SYNOLOGY.equals(profile.nasVendor)) {
                JSONObject data = synology("SYNO.FileStation.Sharing", 3, "list", sid,
                        new FormBody.Builder().add("offset", "0").add("limit", "500"));
                JSONArray values = data.optJSONArray("links");
                if (values != null) for (int i = 0; i < values.length(); i++) {
                    JSONObject item = values.getJSONObject(i);
                    String path = item.optString("path", "");
                    if (!NasSharePolicy.inAllowedRoot(profile, path)) continue;
                    String owner = item.optString("link_owner", "");
                    if (!owner.isEmpty() && !owner.equals(profile.nasApiUser)) continue;
                    result.add(checkedLink(item.optString("id", ""), path,
                            item.optString("url", ""),
                            synologyExpiry(item.optString("date_expired", "0"))));
                }
            } else {
                JSONObject data = qnap("get_share_list", sid,
                        new FormBody.Builder().add("start", "0")
                                .add("limit", "500").add("sort", "start_time")
                                .add("dir", "DESC"));
                JSONArray values = data.optJSONArray("datas");
                if (values != null) for (int i = 0; i < values.length(); i++) {
                    JSONObject item = values.getJSONObject(i);
                    String path = item.optString("link_name", "");
                    if (path.isEmpty()) path = item.optString("filename", "");
                    if (!NasSharePolicy.inAllowedRoot(profile, path)) continue;
                    String owner = item.optString("creator", "");
                    if (!owner.isEmpty() && !owner.equals(profile.nasApiUser)) continue;
                    result.add(checkedLink(item.optString("ssid", ""), path,
                            item.optString("download_link", ""),
                            item.optLong("expire_time", 0)));
                }
            }
            return result;
        });
    }

    void revoke(Link link) throws Exception {
        if (link == null || !NasSharePolicy.inAllowedRoot(profile, link.path))
            throw new IOException("설정된 공유 폴더 밖의 링크는 해제할 수 없습니다.");
        authenticated(sid -> {
            if (NasSharePolicy.SYNOLOGY.equals(profile.nasVendor)) {
                synology("SYNO.FileStation.Sharing", 3, "delete", sid,
                        new FormBody.Builder().add("id", JSONObject.quote(link.id)));
            } else {
                qnap("delete_share", sid, new FormBody.Builder()
                        .add("file_total", "1").add("download_link", link.url)
                        .add("filename", link.path));
            }
            return null;
        });
    }

    RevokeReport revokeMany(List<Link> selected) throws Exception {
        if (selected == null || selected.isEmpty())
            throw new IllegalArgumentException(L10n.t("해제할 공유 링크를 선택하세요."));
        // Check every target before modifying anything on the remote NAS.
        java.util.LinkedHashMap<String, Link> unique = new java.util.LinkedHashMap<>();
        for (Link link : selected) {
            if (link == null || link.id == null || link.id.isEmpty() ||
                    !NasSharePolicy.inAllowedRoot(profile, link.path))
                throw new IOException("허용하지 않은 공유 링크가 선택되었습니다.");
            unique.put(link.id, link);
        }
        List<Link> targets = new ArrayList<>(unique.values());
        // Authenticate once per batch. A 2FA challenge occurs before any
        // changes, so an OTP retry does not repeat a partially completed batch.
        return authenticated(sid -> revokeEach(targets, link -> {
            if (NasSharePolicy.SYNOLOGY.equals(profile.nasVendor)) {
                synology("SYNO.FileStation.Sharing", 3, "delete", sid,
                        new FormBody.Builder().add("id", JSONObject.quote(link.id)));
            } else {
                qnap("delete_share", sid, new FormBody.Builder()
                        .add("file_total", "1").add("download_link", link.url)
                        .add("filename", link.path));
            }
        }));
    }

    private static long synologyExpiry(String raw) {
        if (raw == null || raw.isEmpty() || "0".equals(raw)) return 0;
        try {
            return LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault())
                    .toEpochSecond();
        } catch (Exception ignored) { return 0; }
    }

    private static Link checkedLink(String id, String path, String url, long expires)
            throws IOException {
        if (id == null || id.isEmpty() || url == null || url.isEmpty())
            throw new IOException("NAS가 올바른 공유 ID 또는 URL을 반환하지 않았습니다.");
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !(parsed.isHttps() || "http".equals(parsed.scheme())) ||
                parsed.username().length() > 0 || parsed.password().length() > 0)
            throw new IOException("NAS가 유효하지 않은 공유 주소를 반환했습니다.");
        return new Link(id, path, url, expires);
    }
}
