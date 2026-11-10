package com.unifiedsupportinbox.testing;

import java.lang.reflect.Type;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Runtime handler inventory and reviewed additive contract candidate, never a production component. */
final class ApiSurfaceContract {
    record Route(String method, String path, HandlerMethod handler) { }
    static List<Route> routes(RequestMappingHandlerMapping mapping) {
        List<Route> result = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> info.getPatternValues().stream()
                .filter(path -> path.startsWith("/api/v1/")).forEach(path -> info.getMethodsCondition().getMethods()
                        .forEach(method -> result.add(new Route(method.name(), path, handler)))));
        return result.stream().sorted(java.util.Comparator.comparing(route -> route.path() + route.method())).toList();
    }
    static ObjectNode expandedContract(List<Route> routes, JsonNode original) {
        var exporter = new ApiSurfaceContract();
        var contract = (ObjectNode) original.deepCopy();
        exporter.schemas = (ObjectNode) contract.path("components").path("schemas");
        var paths = (ObjectNode) contract.path("paths");
        for (var route : routes) {
            var path = paths.withObjectProperty(route.path());
            if (path.has(route.method().toLowerCase())) continue;
            ObjectNode operation = path.putObject(route.method().toLowerCase());
            operation.put("operationId", "api" + route.handler().getBeanType().getSimpleName() + capitalize(route.handler().getMethod().getName()));
            operation.put("summary", route.handler().getBeanType().getSimpleName() + ": " + route.handler().getMethod().getName());
            boolean publicRoute = route.path().startsWith("/api/v1/auth/") || route.path().startsWith("/api/v1/providers/") || route.path().startsWith("/api/v1/provider-callbacks/");
            var security = operation.putArray("security");
            if (!publicRoute) security.addObject().putArray("sessionCookie");
            var parameters = operation.putArray("parameters");
            for (var parameter : route.handler().getMethod().getParameters()) {
                var variable = parameter.getAnnotation(PathVariable.class);
                var query = parameter.getAnnotation(RequestParam.class);
                var header = parameter.getAnnotation(RequestHeader.class);
                if (variable != null || query != null || header != null) {
                    String name = variable != null ? first(variable.name(), variable.value(), parameter.getName())
                            : query != null ? first(query.name(), query.value(), parameter.getName())
                            : first(header.name(), header.value(), parameter.getName());
                    var entry = parameters.addObject().put("name", name).put("in", variable != null ? "path" : query != null ? "query" : "header");
                    entry.put("required", variable != null || (query != null ? query.required() && query.defaultValue().equals(ValueConstants.DEFAULT_NONE) : header.required()));
                    entry.set("schema", exporter.schema(parameter.getParameterizedType()));
                }
                if (parameter.isAnnotationPresent(RequestBody.class)) {
                    operation.putObject("requestBody").put("required", parameter.getAnnotation(RequestBody.class).required())
                            .putObject("content").putObject("application/json").set("schema", parameter.getType() == byte[].class
                                    ? exporter.json.createObjectNode().put("type", "object").put("additionalProperties", true) : exporter.schema(parameter.getParameterizedType()));
                }
            }
            JavaType response = exporter.json.getTypeFactory().constructType(route.handler().getMethod().getGenericReturnType());
            if (ResponseEntity.class.isAssignableFrom(response.getRawClass())) response = response.containedTypeOrUnknown(0);
            var status = route.handler().getMethodAnnotation(ResponseStatus.class);
            String code = status == null ? (response.getRawClass() == Void.class || response.getRawClass() == void.class ? "204" : "200") : Integer.toString(status.code().value());
            if (route.handler().getMethod().getName().equals("requestReset") || route.handler().getBeanType().getSimpleName().equals("SlackResyncController") && route.method().equals("POST")) code = "202";
            if (route.path().contains("provider-callbacks/") || route.path().contains("providers/")) code = "200";
            var responses = operation.putObject("responses");
            var success = responses.putObject(code).put("description", "Successful response");
            if (!code.equals("204") && response.getRawClass() != Void.class) success.putObject("content")
                    .putObject(org.springframework.core.io.Resource.class.isAssignableFrom(response.getRawClass()) ? "application/octet-stream" : "application/json")
                    .set("schema", exporter.schema(response));
            for (String error : List.of("400", "401", "403", "404", "409", "429")) {
                responses.putObject(error).put("description", "Controlled API problem").putObject("content").putObject("application/problem+json")
                        .putObject("schema").put("$ref", "#/components/schemas/ApiProblem");
            }
        }
        return contract;
    }
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode schemas;
    private ObjectNode schema(Type type) { return schema(json.getTypeFactory().constructType(type)); }
    private ObjectNode schema(JavaType type) {
        Class<?> raw = type.getRawClass();
        var value = json.createObjectNode();
        if (org.springframework.core.io.Resource.class.isAssignableFrom(raw)) return value.put("type", "string").put("format", "binary");
        if (raw == java.time.Duration.class) return value.put("type", "string").put("format", "duration");
        if (raw == String.class || raw == Character.class || raw == char.class) return value.put("type", "string");
        if (raw == UUID.class) return value.put("type", "string").put("format", "uuid");
        if (Temporal.class.isAssignableFrom(raw)) return value.put("type", "string").put("format", raw == java.time.Instant.class || raw == java.time.OffsetDateTime.class ? "date-time" : "date");
        if (raw == Boolean.class || raw == boolean.class) return value.put("type", "boolean");
        if (raw.isPrimitive() || Number.class.isAssignableFrom(raw)) return value.put("type", raw == double.class || raw == Double.class ? "number" : "integer");
        if (raw.isEnum()) { value.put("type", "string"); var options = value.putArray("enum"); for (Object option : raw.getEnumConstants()) options.add(((Enum<?>) option).name()); return value; }
        if (type.isCollectionLikeType() || type.isArrayType()) { value.put("type", "array").set("items", schema(type.getContentType())); return value; }
        if (raw == Object.class || JsonNode.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) return value.put("type", "object").put("additionalProperties", true);
        if (!raw.isRecord()) throw new IllegalArgumentException("Review unsupported response type " + raw.getName());
        String name = "Api" + (raw.getDeclaringClass() == null ? "" : raw.getDeclaringClass().getSimpleName()) + raw.getSimpleName();
        if (!schemas.has(name)) {
            var definition = schemas.putObject(name).put("type", "object").put("additionalProperties", false);
            var properties = definition.putObject("properties");
            var required = definition.putArray("required");
            for (var component : raw.getRecordComponents()) {
                properties.set(component.getName(), schema(json.getTypeFactory().resolveMemberType(component.getGenericType(), type.getBindings())));
                if (component.getType().isPrimitive() || java.util.Arrays.stream(component.getAnnotations()).anyMatch(annotation ->
                        List.of("NotBlank", "NotNull", "NotEmpty").contains(annotation.annotationType().getSimpleName()))) required.add(component.getName());
            }
        }
        return value.put("$ref", "#/components/schemas/" + name);
    }
    private static String capitalize(String value) { return Character.toUpperCase(value.charAt(0)) + value.substring(1); }
    private static String first(String... values) { for (String value : values) if (!value.isBlank()) return value; throw new IllegalArgumentException(); }
}
