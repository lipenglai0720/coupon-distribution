package cn.quanshu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
  private final Accounts accounts;

  AuthController(Accounts accounts) {
    this.accounts = accounts;
  }

  record Credentials(
      @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 72) String password) {}

  @PostMapping("/login")
  ResponseEntity<Accounts.Session> login(@RequestBody @Valid Credentials credentials) {
    var session =
        accounts
            .login(credentials.username(), credentials.password())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码无效，或请稍后再试"));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session);
  }

  @GetMapping("/me")
  Accounts.Identity me(@AuthenticationPrincipal Accounts.Identity identity) {
    if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return identity;
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(
      @RequestHeader(name = "Authorization", required = false) String header) {
    accounts.logout(ApiSecurity.bearer(header));
    return ResponseEntity.noContent().build();
  }
}
