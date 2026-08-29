package com.fitpilot.common.operations;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.security.SecureTokenMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class OperationsAuthorizer {
    private final OperationsProperties properties;
    public OperationsAuthorizer(OperationsProperties properties){this.properties=properties;}
    public void authorize(String candidate,String... legacyTokens){
        boolean valid=SecureTokenMatcher.matches(properties.getToken(),candidate)||Arrays.stream(legacyTokens).anyMatch(token->SecureTokenMatcher.matches(token,candidate));
        if(!valid)throw new BusinessException(ErrorCode.ACCESS_DENIED,"invalid operations token",HttpStatus.FORBIDDEN);
    }
}
