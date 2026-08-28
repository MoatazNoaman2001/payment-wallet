package com.moataz.paymentwallet.web.dto;

import com.moataz.paymentwallet.user.dto.CounterRegistrationRequest;
import com.moataz.paymentwallet.user.dto.KycSubmissionRequest;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record CounterRegistrationForm(

        @NotBlank(message = "full name is required") @Size(max = 150) String fullName,

        @NotBlank(message = "email is required")
        @Email(message = "must be a well-formed email address")
        @Size(max = 255) String email,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be 7-15 digits, optional leading +")
        String phone,

        @NotBlank(message = "a temporary password is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password,

        @NotBlank(message = "national id is required")
        @Pattern(regexp = "^[0-9]{8,20}$", message = "must be 8-20 digits")
        String nationalId,

        @NotNull(message = "date of birth is required")
        @Past(message = "must be in the past")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate dateOfBirth,

        @Size(max = 255) String address
) {
    public CounterRegistrationRequest toRequest() {
        return new CounterRegistrationRequest(
                new RegisterUserRequest(email, phone, password, fullName),
                new KycSubmissionRequest(nationalId, dateOfBirth, address));
    }

    public static CounterRegistrationForm empty() {
        return new CounterRegistrationForm(null, null, null, null, null, null, null);
    }
}
