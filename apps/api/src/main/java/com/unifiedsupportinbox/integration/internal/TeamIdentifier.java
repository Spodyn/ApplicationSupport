package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.ApiProblemException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class TeamIdentifier {
    static UUID parse(String input,String expectedTenant) {
        if(input==null||input.length()>4096)throw invalid();
        String value=input.strip();
        if(value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return UUID.fromString(value);
        try {
            URI link=URI.create(value);
            if(!"https".equals(link.getScheme())||!("teams.microsoft.com".equalsIgnoreCase(link.getHost())||"teams.cloud.microsoft".equalsIgnoreCase(link.getHost()))||link.getUserInfo()!=null
                    ||link.getPort()!=-1||link.getFragment()!=null||!link.getPath().matches("/l/team/[^/]+/conversations/?"))throw invalid();
            Map<String,String> query=new HashMap<>();
            for(String pair:Objects.toString(link.getRawQuery(),"").split("&")) {
                String[] p=pair.split("=",2);if(p.length!=2)continue;
                String key=URLDecoder.decode(p[0],StandardCharsets.UTF_8),item=URLDecoder.decode(p[1],StandardCharsets.UTF_8);
                if(query.putIfAbsent(key,item)!=null)throw invalid();
            }
            String tenant=query.get("tenantId");
            if(tenant!=null&&expectedTenant!=null&&!expectedTenant.isBlank()&&!UUID.fromString(tenant).equals(UUID.fromString(expectedTenant)))throw invalid();
            String group=query.get("groupId");
            if(group==null||!group.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();
            return UUID.fromString(group);
        } catch(IllegalArgumentException e){throw invalid();}
    }
    private static ApiProblemException invalid(){return ApiProblemException.validationFailed("Enter a Team GUID or an HTTPS Teams team link containing groupId for this tenant.");}
}
