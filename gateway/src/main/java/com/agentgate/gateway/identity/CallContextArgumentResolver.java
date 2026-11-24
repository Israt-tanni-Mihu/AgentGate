package com.agentgate.gateway.identity;

import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Builds a {@link CallContext} from (1) the verified agent token and (2) the X-On-Behalf-Of header.
 * Controllers just declare a CallContext parameter; they never read tokens or headers themselves.
 */
public class CallContextArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String ON_BEHALF_OF_HEADER = "X-On-Behalf-Of";

    /** Simple user ids only: blocks smuggling tricks like "cust-1, cust-2", spaces, quotes or very long values. */
    private static final Pattern END_USER_ID = Pattern.compile("^[A-Za-z0-9._@:-]{1,128}$");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CallContext.class.equals(parameter.getParameterType());
    }

    @Override
    public CallContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof AgentAuthenticationToken agent)) {
            throw new AccessDeniedException("Only authenticated agents have a call context");
        }
        String endUserId = readEndUser(webRequest.getHeaderValues(ON_BEHALF_OF_HEADER));
        return new CallContext(agent.getName(), endUserId, agent.getClearance());
    }

    static String readEndUser(String[] headerValues) {
        if (headerValues == null || headerValues.length == 0) {
            return null;   // the agent acts for itself
        }
        if (headerValues.length > 1) {
            throw new InvalidOnBehalfOfException("Send the " + ON_BEHALF_OF_HEADER + " header at most once");
        }
        String value = headerValues[0].trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!END_USER_ID.matcher(value).matches()) {
            throw new InvalidOnBehalfOfException(
                    ON_BEHALF_OF_HEADER + " must be 1-128 characters: letters, digits, '.', '_', '@', ':' or '-'");
        }
        return value;
    }
}
