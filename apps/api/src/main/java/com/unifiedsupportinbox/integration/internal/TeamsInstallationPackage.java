package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.ApiProblemException;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Environment-specific identifiers only. No bot secret/token is ever packaged. */
@Component
class TeamsInstallationPackage {
    private final String appId;
    TeamsInstallationPackage(@Value("${usi.providers.teams.app-id:}")String appId){this.appId=appId;}
    byte[] create(String clientId,String callback) {
        try {
            String client=UUID.fromString(clientId).toString();String app=appId.isBlank()?client:UUID.fromString(appId).toString();
            URI endpoint=URI.create(callback);
            if(!"https".equals(endpoint.getScheme())||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null)
                throw ApiProblemException.validationFailed("Teams installation requires this instance's public HTTPS callback.");
            String origin="https://"+endpoint.getRawAuthority();
            String manifest;
            try(var source=getClass().getResourceAsStream("/teams/manifest.template.json")) {
                if(source==null)throw new IllegalStateException("Teams installation template is unavailable.");
                manifest=new String(source.readAllBytes(),StandardCharsets.UTF_8).replace("__TEAMS_APP_ID__",app)
                        .replace("__TEAMS_BOT_CLIENT_ID__",client).replace("https://example.invalid",origin);
            }
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(var zip=new ZipOutputStream(output)) {
                entry(zip,"manifest.json",manifest.getBytes(StandardCharsets.UTF_8));
                entry(zip,"color.png",icon(192,false));entry(zip,"outline.png",icon(32,true));
            }
            return output.toByteArray();
        } catch(IOException e){throw new IllegalStateException("Teams installation package could not be generated.",e);}
    }
    private static void entry(ZipOutputStream zip,String name,byte[] data)throws IOException {
        ZipEntry entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);zip.write(data);zip.closeEntry();
    }
    private static byte[] icon(int size,boolean outline)throws IOException {
        var raw=new ByteArrayOutputStream();
        for(int y=0;y<size;y++) {raw.write(0);for(int x=0;x<size;x++) {
            double px=x/(double)size,py=y/(double)size;
            boolean ink=(px>.2&&px<.8&&(py>.23&&py<.29||py>.69&&py<.75))
                    ||(py>.23&&py<.75&&(px>.2&&px<.26||px>.74&&px<.8))
                    ||(px>.37&&px<.63&&py>.48&&py<.54);
            raw.write(ink?255:17);raw.write(ink?255:24);raw.write(ink?255:39);raw.write(outline&&!ink?0:255);
        }}
        var compressed=new ByteArrayOutputStream();try(var deflate=new DeflaterOutputStream(compressed)){deflate.write(raw.toByteArray());}
        var result=new ByteArrayOutputStream();var bytes=new DataOutputStream(result);
        bytes.write(new byte[]{(byte)137,80,78,71,13,10,26,10});var header=new ByteArrayOutputStream();var h=new DataOutputStream(header);
        h.writeInt(size);h.writeInt(size);h.write(new byte[]{8,6,0,0,0});
        chunk(bytes,"IHDR",header.toByteArray());chunk(bytes,"IDAT",compressed.toByteArray());chunk(bytes,"IEND",new byte[0]);return result.toByteArray();
    }
    private static void chunk(DataOutputStream out,String type,byte[] data)throws IOException {
        byte[] kind=type.getBytes(StandardCharsets.US_ASCII);CRC32 crc=new CRC32();crc.update(kind);crc.update(data);
        out.writeInt(data.length);out.write(kind);out.write(data);out.writeInt((int)crc.getValue());
    }
}
