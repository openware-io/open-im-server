package io.openware.im.user.api.dto.request;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class PhoneLengthValidationTest {
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void shouldAcceptAtMostThirtyPhoneCharactersForRegistrationAndProfile() {
    String thirtyDigits = "123456789012345678901234567890";
    RegisterRequest registerRequest = new RegisterRequest();
    registerRequest.setUsername("phone-length-test");
    registerRequest.setPassword("password123");
    registerRequest.setEmail("phone-length-test@example.com");
    registerRequest.setPhone(thirtyDigits);
    UpdateProfileRequest updateRequest = new UpdateProfileRequest();
    updateRequest.setPhone(thirtyDigits);

    assertTrue(validator.validate(registerRequest).isEmpty());
    assertTrue(validator.validate(updateRequest).isEmpty());
  }

  @Test
  void shouldRejectMoreThanThirtyPhoneCharacters() {
    RegisterRequest request = new RegisterRequest();
    request.setUsername("phone-length-test");
    request.setPassword("password123");
    request.setEmail("phone-length-test@example.com");
    request.setPhone("1234567890123456789012345678901");

    assertFalse(validator.validate(request).isEmpty());
  }
}
