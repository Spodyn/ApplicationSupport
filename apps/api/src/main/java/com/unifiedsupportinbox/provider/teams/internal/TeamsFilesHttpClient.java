package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.ProviderAttachmentMetadata;
import com.unifiedsupportinbox.provider.ProviderAttachmentGateway.ContentSource;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/** Fixed Graph origin, bounded streamed file I/O, no bearer forwarding to storage redirects. */
@Component
class TeamsFilesHttpClient {
    static final long MAX_FILE=25L*1024*1024;
    private static final String GRAPH_SCOPE="https://graph.microsoft.com/.default";
    private static final String BOT_SCOPE="https://api.botframework.com/.default";
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES=
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"teams-file-deadline");t.setDaemon(true);return t;});
    private final HttpClient http;
    private final ObjectMapper json;
    private final TeamsAccessTokens tokens;
    private final URI graph;
    private final Set<String> syntheticOrigins;

    @Autowired TeamsFilesHttpClient(ObjectMapper json,TeamsAccessTokens tokens) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
                json,tokens,URI.create("https://graph.microsoft.com/v1.0/"),Set.of());
    }
    // Only synthetic HTTP tests can supply alternate origins; no runtime setting bypasses trust.
    TeamsFilesHttpClient(HttpClient http,ObjectMapper json,TeamsAccessTokens tokens,URI graph,Set<String> syntheticOrigins) {
        this.http=http;this.json=json;this.tokens=tokens;this.graph=graph;this.syntheticOrigins=Set.copyOf(syntheticOrigins);
    }
    record Source(String key,String name,String type,String kind,int index) { }
    record StoredFile(String driveId,String itemId,String webUrl,String name) { }
    record FileLocation(String driveId,String folderId,Set<String> hosts) {
        FileLocation { hosts=Set.copyOf(hosts); }
    }
    record Download(Path path,String filename,String contentType,long size,String sha256) implements AutoCloseable {
        @Override public void close() {try {Files.deleteIfExists(path);} catch(IOException ignored) {}}
    }

    List<Source> discover(UUID integration,JsonNode activity) {
        var result=new ArrayList<Source>();
        var attachments=activity.path("attachments");
        boolean hasHtml=false,connectorImage=false;
        for(int i=0;i<attachments.size();i++) {
            var a=attachments.get(i);String type=a.path("contentType").asText("").toLowerCase(Locale.ROOT);
            if(type.startsWith("image/") && a.path("contentUrl").isTextual()) {
                URI image=URI.create(a.path("contentUrl").asText());
                if("https".equals(image.getScheme()) && Set.of("smba.trafficmanager.net","smba.infra.teams.microsoft.com").contains(image.getHost())) {
                    connectorImage=true;hasHtml=true;continue;
                }
                result.add(new Source("inline:"+i,a.path("name").asText("image-"+(i+1)+imageExtension(type)),type,"INLINE",i));
            }
            if("text/html".equals(type) || "reference".equals(type)) hasHtml=true;
        }
        if(hasHtml) {
            var message=message(integration,activity);
            for(var a:message.path("attachments")) if("reference".equals(a.path("contentType").asText()))
                result.add(new Source("reference:"+required(a,"id"),a.path("name").asText("attachment"),"application/octet-stream","REFERENCE",-1));
            // Connector may provide only HTML for pasted images. Enumerate provider-owned
            // hosted content rather than following image URLs from customer HTML.
            if(result.stream().noneMatch(s->"INLINE".equals(s.kind())) && (connectorImage||message.path("body").path("content").asText("").toLowerCase(Locale.ROOT).contains("<img"))) {
                var hosted=get(integration,messagePath(activity)+"/hostedContents");
                if(hosted.hasNonNull("@odata.nextLink"))throw ProviderAttachmentException.validation("POLICY_ATTACHMENT_COUNT");
                int image=0;
                for(var a:hosted.path("value")) {
                    String type=a.path("contentType").asText("image/*");
                    if(type.startsWith("image/"))result.add(new Source("hosted:"+required(a,"id"),"image-"+(++image)+imageExtension(type),type,"HOSTED",-1));
                }
            }
        }
        return List.copyOf(result);
    }
    JsonNode message(UUID integration,JsonNode a) {
        return get(integration,messagePath(a));
    }
    private String messagePath(JsonNode a) {
        String id=required(a,"id"), conversation=required(a.path("conversation"),"id");
        if("groupChat".equals(a.path("conversation").path("conversationType").asText()))
            return "chats/"+enc(conversation)+"/messages/"+enc(id);
        String team=required(a.path("channelData").path("team"),"aadGroupId");
        UUID.fromString(team);
        var normalized=TeamsActivity.parse(a,"");
        if(normalized==null) throw ProviderAttachmentException.validation("TEAMS_UNSUPPORTED_FILE_CONTEXT");
        String path="teams/"+enc(team)+"/channels/"+enc(normalized.channelId())+"/messages/"+enc(normalized.thread());
        if(!id.equals(normalized.thread())) path+="/replies/"+enc(id);
        return path;
    }
    Download download(UUID integration,JsonNode activity,Source source,FileLocation location) {
        if("HOSTED".equals(source.kind())) {
            if(!source.key().startsWith("hosted:"))throw ProviderAttachmentException.validation("TEAMS_IMAGE_REFERENCE_INVALID");
            return spool(endpoint(messagePath(activity)+"/hostedContents/"+enc(source.key().substring(7))+"/$value"),
                    tokens.token(integration,GRAPH_SCOPE),source.name(),source.type(),location.hosts());
        }
        if("INLINE".equals(source.kind())) {
            var a=activity.path("attachments").get(source.index());
            if(a==null || !a.path("contentType").asText("").startsWith("image/"))
                throw ProviderAttachmentException.validation("TEAMS_IMAGE_REFERENCE_INVALID");
            URI uri=URI.create(required(a,"contentUrl"));
            boolean graphHost="graph.microsoft.com".equalsIgnoreCase(uri.getHost()) || origin(uri).equals(origin(graph));
            if(!graphHost && !isAsm(uri)) throw ProviderAttachmentException.unsafeRedirect("TEAMS_UNTRUSTED_FILE_SOURCE");
            return spool(uri,tokens.token(integration,graphHost?GRAPH_SCOPE:BOT_SCOPE),source.name(),source.type(),location.hosts());
        }
        var reference=java.util.stream.StreamSupport.stream(message(integration,activity).path("attachments").spliterator(),false)
                .filter(a->source.key().equals("reference:"+a.path("id").asText())).findFirst()
                .orElseThrow(()->ProviderAttachmentException.notFound("TEAMS_FILE_REFERENCE_NOT_FOUND"));
        URI url=URI.create(required(reference,"contentUrl").replace(" ","%20"));
        requireStorageHost(url,location.hosts());
        // Selected-site access need not support the broad /shares resolver. Resolve only
        // within the explicitly configured drive, using its provider-returned web root.
        if(location.driveId()==null)throw ProviderAttachmentException.validation("TEAMS_FILE_STORAGE_CONFIGURATION_REQUIRED");
        var driveInfo=get(integration,"drives/"+enc(location.driveId())+"?$select=id,webUrl");
        URI driveWeb=URI.create(required(driveInfo,"webUrl").replace(" ","%20"));requireStorageHost(driveWeb,location.hosts());
        String root=driveWeb.getPath().replaceAll("/+$","")+"/",path=url.normalize().getPath();
        if(!url.getHost().equalsIgnoreCase(driveWeb.getHost())||!path.startsWith(root))
            throw ProviderAttachmentException.validation("TEAMS_FILE_SOURCE_OUTSIDE_CONFIGURED_STORAGE");
        String relative=Arrays.stream(path.substring(root.length()).split("/")).map(TeamsFilesHttpClient::enc).collect(java.util.stream.Collectors.joining("/"));
        if(Arrays.stream(path.substring(root.length()).split("/")).anyMatch(p->p.equals(".")||p.equals("..")))
            throw ProviderAttachmentException.validation("TEAMS_FILE_SOURCE_OUTSIDE_CONFIGURED_STORAGE");
        JsonNode file=get(integration,"drives/"+enc(location.driveId())+"/root:/"+relative+"?$select=id,name,size,file,parentReference");
        long size=file.path("size").asLong(-1);
        if(size<0 || size>MAX_FILE) throw ProviderAttachmentException.validation("POLICY_FILE_SIZE");
        String drive=required(file.path("parentReference"),"driveId"),item=required(file,"id");
        var downloaded=spool(endpoint("drives/"+enc(drive)+"/items/"+enc(item)+"/content"),tokens.token(integration,GRAPH_SCOPE),
                file.path("name").asText(source.name()),file.path("file").path("mimeType").asText(source.type()),location.hosts());
        if(downloaded.size()!=size){downloaded.close();throw ProviderAttachmentException.unavailable("TEAMS_FILE_SIZE_MISMATCH",null);}
        return downloaded;
    }
    StoredFile upload(UUID integration,FileLocation location,String deterministicName,String originalName,
            String contentType,long size,ContentSource source) {
        if(location.driveId()==null || location.folderId()==null || location.hosts().isEmpty())
            throw ProviderAttachmentException.validation("TEAMS_FILE_STORAGE_CONFIGURATION_REQUIRED");
        if(size<0 || size>MAX_FILE) throw ProviderAttachmentException.validation("POLICY_FILE_SIZE");
        var publisher=HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(()-> {
            try {return source.open();}catch(IOException failure){throw new UncheckedIOException(failure);}
        }),size);
        var request=HttpRequest.newBuilder(endpoint("drives/"+enc(location.driveId())+"/items/"+enc(location.folderId())+":/"+enc(deterministicName)+":/content"))
                .timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+tokens.token(integration,GRAPH_SCOPE))
                .header("Content-Type",contentType).PUT(publisher).build();
        JsonNode result=readJson(send(request));
        String web=required(result,"webUrl");requireStorageHost(URI.create(web),location.hosts());
        return new StoredFile(location.driveId(),required(result,"id"),web,originalName);
    }
    StoredFile stored(UUID integration,String drive,String item,String originalName,FileLocation location) {
        var data=get(integration,"drives/"+enc(drive)+"/items/"+enc(item)+"?$select=id,webUrl");
        String web=required(data,"webUrl");requireStorageHost(URI.create(web),location.hosts());
        return new StoredFile(drive,required(data,"id"),web,originalName);
    }
    private JsonNode get(UUID integration,String path) {
        return readJson(send(HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(20))
                .header("Authorization","Bearer "+tokens.token(integration,GRAPH_SCOPE)).GET().build()));
    }
    private URI endpoint(String path) {return graph.resolve(path);}
    private HttpResponse<InputStream> send(HttpRequest request) {
        try {return http.send(request,HttpResponse.BodyHandlers.ofInputStream());}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw ProviderAttachmentException.timeout("TEAMS_FILE_INTERRUPTED",null);}
        catch(IOException|UncheckedIOException failure){throw ProviderAttachmentException.unavailable("TEAMS_FILE_TRANSFER_UNAVAILABLE",null);}
    }
    private JsonNode readJson(HttpResponse<InputStream> response) {
        var deadline=DEADLINES.schedule(()->{try{response.body().close();}catch(IOException ignored){}},30,java.util.concurrent.TimeUnit.SECONDS);
        try(var body=response.body()) {
            requireStatus(response);
            byte[] bytes=body.readNBytes(2*1024*1024+1);
            if(bytes.length>2*1024*1024) throw ProviderAttachmentException.validation("TEAMS_FILE_METADATA_TOO_LARGE");
            return json.readTree(bytes);
        }catch(IOException failure){throw ProviderAttachmentException.unavailable("TEAMS_FILE_TRANSFER_UNAVAILABLE",null);}
        finally{deadline.cancel(false);}
    }
    private Download spool(URI source,String bearer,String filename,String contentType,Set<String> hosts) {
        URI current=source;String authorization=bearer;
        for(int redirect=0;redirect<=3;redirect++) {
            requireDownloadUri(current,hosts);
            var builder=HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(20)).GET();
            if(authorization!=null) builder.header("Authorization","Bearer "+authorization);
            var response=send(builder.build());
            try(var body=response.body()) {
                if(Set.of(301,302,303,307,308).contains(response.statusCode())) {
                    if(redirect==3) throw ProviderAttachmentException.unsafeRedirect("TEAMS_FILE_REDIRECT_LIMIT");
                    URI next=current.resolve(response.headers().firstValue("Location")
                            .orElseThrow(()->ProviderAttachmentException.unsafeRedirect("TEAMS_FILE_REDIRECT_INVALID")));
                    // SharePoint download URLs carry their own scoped authorization. Never forward the Graph token.
                    if(!origin(current).equals(origin(next))) authorization=null;
                    current=next;continue;
                }
                requireStatus(response);
                long declared=response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if(declared>MAX_FILE) throw ProviderAttachmentException.validation("POLICY_FILE_SIZE");
                Path file=Files.createTempFile("usi-teams-attachment-",".bin");
                boolean complete=false;
                var deadline=DEADLINES.schedule(()->{try{body.close();}catch(IOException ignored){}},30,java.util.concurrent.TimeUnit.SECONDS);
                try {
                    var digest=MessageDigest.getInstance("SHA-256");long count=0;
                    try(var output=Files.newOutputStream(file)) {
                        byte[] buffer=new byte[16384];int n;
                        while((n=body.read(buffer))!=-1) {
                            count+=n;if(count>MAX_FILE) throw ProviderAttachmentException.validation("POLICY_FILE_SIZE");
                            digest.update(buffer,0,n);output.write(buffer,0,n);
                        }
                    }
                    if(declared>=0 && declared!=count) throw ProviderAttachmentException.unavailable("TEAMS_FILE_SIZE_MISMATCH",null);
                    String actualType=contentType,actualName=filename;
                    if("image/*".equals(contentType)) {
                        actualType=response.headers().firstValue("Content-Type").orElse("application/octet-stream").split(";",2)[0].strip().toLowerCase(Locale.ROOT);
                        if(!actualType.startsWith("image/")||"image/*".equals(actualType))throw ProviderAttachmentException.validation("TEAMS_IMAGE_CONTENT_TYPE_INVALID");
                        actualName=filename.replaceFirst("\\.[^.]+$","")+imageExtension(actualType);
                    }
                    var metadata=new ProviderAttachmentMetadata("teams-download",actualName,actualType,count);
                    complete=true;return new Download(file,metadata.filename(),metadata.contentType(),count,HexFormat.of().formatHex(digest.digest()));
                } finally {deadline.cancel(false);if(!complete)Files.deleteIfExists(file);}
            } catch(IOException failure){throw ProviderAttachmentException.unavailable("TEAMS_FILE_TRANSFER_UNAVAILABLE",null);}
            catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
        throw ProviderAttachmentException.unsafeRedirect("TEAMS_FILE_REDIRECT_LIMIT");
    }
    private void requireDownloadUri(URI uri,Set<String> hosts) {
        if(syntheticOrigins.contains(origin(uri))) return;
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getFragment()!=null
                || uri.getPort()!=-1 && uri.getPort()!=443) throw ProviderAttachmentException.unsafeRedirect("TEAMS_UNTRUSTED_FILE_SOURCE");
        if(!"graph.microsoft.com".equalsIgnoreCase(uri.getHost()) && !isAsm(uri) && !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)))
            throw ProviderAttachmentException.unsafeRedirect("TEAMS_UNTRUSTED_FILE_SOURCE");
        try {
            for(var address:InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes=address.getAddress();
                if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()
                        ||address.isMulticastAddress()||bytes.length==16&&(bytes[0]&0xfe)==0xfc
                        ||bytes.length==4&&(bytes[0]&255)==100&&(bytes[1]&0xc0)==64)
                    throw ProviderAttachmentException.unsafeRedirect("TEAMS_UNTRUSTED_FILE_SOURCE");
            }
        }catch(UnknownHostException failure){throw ProviderAttachmentException.unavailable("TEAMS_FILE_DNS_UNAVAILABLE",null);}
    }
    private void requireStorageHost(URI uri,Set<String> hosts) {
        if(syntheticOrigins.contains(origin(uri))) return;
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getFragment()!=null
                ||uri.getPort()!=-1&&uri.getPort()!=443||!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)))
            throw ProviderAttachmentException.validation("TEAMS_FILE_STORAGE_CONFIGURATION_REQUIRED");
    }
    private static boolean isAsm(URI uri) {
        return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getHost().endsWith(".asm.skype.com")
                &&uri.getRawUserInfo()==null&&uri.getFragment()==null&&(uri.getPort()==-1||uri.getPort()==443);
    }
    private static void requireStatus(HttpResponse<?> response) {
        int status=response.statusCode();
        if(status==429) throw ProviderAttachmentException.rateLimited("TEAMS_FILE_RATE_LIMITED",retryAfter(response));
        if(status>=500) throw ProviderAttachmentException.unavailable("TEAMS_FILE_TRANSFER_UNAVAILABLE",null);
        if(status==401) throw ProviderAttachmentException.authentication("TEAMS_AUTH_FAILED");
        if(status==403) throw ProviderAttachmentException.authentication("TEAMS_FILE_PERMISSION_REQUIRED");
        if(status==404) throw ProviderAttachmentException.notFound("TEAMS_FILE_NOT_FOUND");
        if(status<200||status>=300) throw ProviderAttachmentException.validation("TEAMS_FILE_REJECTED");
    }
    static Duration retryAfter(HttpResponse<?> response) {
        String value=response.headers().firstValue("Retry-After").orElse("5");
        try{return Duration.ofSeconds(Math.max(1,Long.parseLong(value)));}
        catch(NumberFormatException ignored){try{return Duration.ofSeconds(Math.max(1,java.time.Duration.between(java.time.Instant.now(),java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds()));}
            catch(java.time.DateTimeException invalid){return Duration.ofSeconds(5);}}
    }
    private static String required(JsonNode node,String key) {
        String value=node.path(key).asText("");
        if(value.isBlank()||value.length()>8192)throw ProviderAttachmentException.validation("TEAMS_FILE_METADATA_INVALID");
        return value;
    }
    static String enc(String value){return TeamsConnectorClient.encode(value);}
    private static String imageExtension(String type){return switch(type){
        case "image/jpeg","image/jpg","image/pjpeg"->".jpg";case "image/gif"->".gif";case "image/webp"->".webp";
        case "image/avif"->".avif";case "image/bmp","image/x-ms-bmp","image/x-bmp"->".bmp";
        case "image/tiff","image/x-tiff"->".tif";case "image/heic"->".heic";case "image/heif"->".heif";default->".png";};}
    private static String origin(URI uri){return uri.getScheme()+"://"+uri.getAuthority();}
}
