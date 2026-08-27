package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice(basePackages = "com.moataz.paymentwallet.web")
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String denied(Model model) {
        model.addAttribute("status", 403);
        model.addAttribute("heading", "Not yours");
        model.addAttribute("message", "You do not have access to this page.");
        return "error/page";
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NotFoundException ex, Model model) {
        model.addAttribute("status", 404);
        model.addAttribute("heading", "Not found");
        model.addAttribute("message", ex.getMessage());
        return "error/page";
    }

    @ExceptionHandler(BusinessRuleException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public String rejected(BusinessRuleException ex, Model model) {
        model.addAttribute("status", 422);
        model.addAttribute("heading", "Cannot do that");
        model.addAttribute("message", ex.getMessage());
        return "error/page";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Exception ex, Model model) {
        log.error("Unhandled exception rendering a page", ex);
        model.addAttribute("status", 500);
        model.addAttribute("heading", "Something broke");
        model.addAttribute("message", "Unexpected error. The details are in the server log.");
        return "error/page";
    }
}
