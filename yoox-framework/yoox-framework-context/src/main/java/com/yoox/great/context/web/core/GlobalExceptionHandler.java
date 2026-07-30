package com.yoox.great.context.web.core;

import com.yoox.great.context.response.HttpResultResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

@Slf4j
@ControllerAdvice
@ResponseBody
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public HttpResultResponse exceptionHandler(Exception exception) {
        log.error("Unhandled request exception", exception);
        return HttpResultResponse.error(exception.getLocalizedMessage());
    }

    @ExceptionHandler(NullPointerException.class)
    public HttpResultResponse nullPointerExceptionHandler(NullPointerException exception) {
        log.error("Null object encountered", exception);
        return HttpResultResponse.error("A null object appeared.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public HttpResultResponse methodArgumentNotValidExceptionHandler(BindException exception) {
        if (exception.getFieldError() == null) {
            return HttpResultResponse.error("Invalid request parameters.");
        }
        return HttpResultResponse.error(
                exception.getFieldError().getField() + exception.getFieldError().getDefaultMessage());
    }
}
