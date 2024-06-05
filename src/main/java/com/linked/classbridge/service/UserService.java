package com.linked.classbridge.service;

import static com.linked.classbridge.type.ErrorCode.ALREADY_EXIST_NICKNAME;
import static com.linked.classbridge.type.ErrorCode.ALREADY_REGISTERED_EMAIL;
import static com.linked.classbridge.type.ErrorCode.PASSWORD_NOT_MATCH;
import static com.linked.classbridge.type.ErrorCode.USER_NOT_FOUND;
import static com.linked.classbridge.util.AgeUtil.calculateAge;

import com.linked.classbridge.domain.Category;
import com.linked.classbridge.domain.User;
import com.linked.classbridge.dto.user.AdditionalInfoDto;
import com.linked.classbridge.dto.user.AuthDto;
import com.linked.classbridge.dto.user.CustomOAuth2User;
import com.linked.classbridge.dto.user.GoogleResponse;
import com.linked.classbridge.dto.user.UserDto;
import com.linked.classbridge.exception.RestApiException;
import com.linked.classbridge.repository.CategoryRepository;
import com.linked.classbridge.repository.UserRepository;
import com.linked.classbridge.type.AuthType;
import com.linked.classbridge.type.CategoryType;
import com.linked.classbridge.type.Gender;
import com.linked.classbridge.type.UserRole;
import com.linked.classbridge.util.CookieUtil;
import com.linked.classbridge.util.JWTUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;

    private final CategoryRepository categoryRepository;

    private final PasswordEncoder passwordEncoder;

    private final JWTUtil jwtUtil;

    private final S3Service s3Service;

    public UserService(UserRepository userRepository, CategoryRepository categoryRepository, PasswordEncoder passwordEncoder,
                       JWTUtil jwtUtil, S3Service s3Service) {

        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.s3Service = s3Service;
    }

    public String checkNickname(String nickname) {

        log.info("Checking if nickname '{}' exists", nickname);

        if(userRepository.existsByNickname(nickname)){
            log.warn("Nickname '{}' already exists", nickname);
            throw new RestApiException(ALREADY_EXIST_NICKNAME);
        }

        log.info("Nickname '{}' is available", nickname);
        return "Use this nickname";
    }

    public String checkEmail(String email) {

        log.info("Checking if email '{}' exists", email);

        if(userRepository.existsByEmail(email)){
            log.warn("Email '{}' is already registered", email);
            throw new RestApiException(ALREADY_REGISTERED_EMAIL);
        }

        log.info("Email '{}' is available", email);
        return "Use this email";
    }

    public Optional<User> findByEmail(String email) {

        log.info("Finding user by email '{}'", email);
        return userRepository.findByEmail(email);
    }

    public UserDto getUserFromGoogle(String accessToken) {

        log.info("Getting user from Google with access token");

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "https://www.googleapis.com/oauth2/v1/userinfo",
                HttpMethod.GET,
                entity,
                Map.class
        );

        Map<String, Object> attributes = response.getBody();
        GoogleResponse googleResponse = new GoogleResponse(attributes);

        UserDto userDto = new UserDto();
        userDto.setProvider("google");
        userDto.setProviderId(googleResponse.getProviderId());
        userDto.setEmail(googleResponse.getEmail());
        userDto.setUsername(googleResponse.getName());
        userDto.setAuthType(AuthType.GOOGLE);

        log.info("Google user '{}' retrieved successfully", userDto.getUsername());
        return userDto;
    }

    public UserDto getUserDto(CustomOAuth2User customOAuth2User) {

        log.info("Getting user DTO from CustomOAuth2User");

        UserDto userDto = new UserDto();
        Map<String, Object> attributes = customOAuth2User.getAttributes();

        userDto.setProvider((String) attributes.get("provider"));
        userDto.setProviderId((String) attributes.get("providerId"));
        userDto.setEmail((String) attributes.get("email"));
        userDto.setUsername((String) attributes.get("username"));
        userDto.setAuthType((AuthType) attributes.get("authType"));

        log.info("User DTO for '{}' created successfully", userDto.getUsername());
        return userDto;
    }

    public void addUser(AuthDto.SignUp signupRequest) {

        log.info("Adding new user with email '{}'", signupRequest.getUserDto().getEmail());

        UserDto userDto = signupRequest.getUserDto();
        AdditionalInfoDto additionalInfoDto = signupRequest.getAdditionalInfoDto();

        if (userRepository.existsByEmail(userDto.getEmail())) {
            log.warn("Email '{}' is already registered", userDto.getEmail());
            throw new RestApiException(ALREADY_REGISTERED_EMAIL);
        }

        if(userRepository.existsByNickname(additionalInfoDto.getNickname())) {
            log.warn("Nickname '{}' already exists", additionalInfoDto.getNickname());
            throw new RestApiException(ALREADY_EXIST_NICKNAME);
        }

        List<UserRole> roles = new ArrayList<>();
        roles.add(UserRole.ROLE_USER);
        Gender gender = additionalInfoDto.getGender() != null ? Gender.valueOf(additionalInfoDto.getGender().toUpperCase()) : null;

        // 관심 카테고리 String -> Category 변환
        List<Category> interests = additionalInfoDto.getInterests().stream()
                .map(interest -> categoryRepository.findByName(CategoryType.valueOf(interest)))
                .collect(Collectors.toList());

        User user = User.builder()
                .provider(userDto.getProvider())
                .providerId(userDto.getProviderId())
                .email(userDto.getEmail())
                .username(userDto.getUsername())
                .authType(userDto.getAuthType())
                .roles(roles)
                .nickname(additionalInfoDto.getNickname())
                .phone(additionalInfoDto.getPhoneNumber())
                .gender(gender)
                .birthDate(additionalInfoDto.getBirthDate())
                .interests(interests)
                .build();

        MultipartFile profileImage = signupRequest.getAdditionalInfoDto().getProfileImage();
        if (profileImage != null) {
            String profileImageUrl = s3Service.uploadUserProfileImage(profileImage);
            user.setProfileImageUrl(profileImageUrl);
        }

        if(signupRequest.getUserDto().getPassword() != null) {
            user.setPassword(passwordEncoder.encode(signupRequest.getUserDto().getPassword()));
        }
        userRepository.save(user);
        log.info("User '{}' added successfully", user.getUsername());

        // 회원가입 완료 후 JWT 토큰 발급
        String token = jwtUtil.createJwt(userDto.getEmail(), userDto.getRoles(), 60 * 60 * 24L * 1000);
        // JWT 토큰을 클라이언트로 전송
        Cookie cookie = CookieUtil.createCookie("Authorization", token);
        HttpServletResponse response = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getResponse();
        if (response != null) {
            response.addCookie(cookie);
            log.info("JWT token added to response for user '{}'", user.getUsername());
        }
    }

    public void signin(AuthDto.SignIn signinRequest) {

        log.info("Signing in user with email '{}'", signinRequest.getEmail());

        User user = userRepository.findByEmail(signinRequest.getEmail())
                .orElseThrow(() -> {
                    log.warn("User with email '{}' not found", signinRequest.getEmail());
                    return new RestApiException(USER_NOT_FOUND);
                });

        if (!passwordEncoder.matches(signinRequest.getPassword(), user.getPassword())) {
            log.warn("Password does not match for user '{}'", signinRequest.getEmail());
            throw new RestApiException(PASSWORD_NOT_MATCH);
        }

        List<String> roles = user.getRoles().stream()
                .map(role -> role.name().substring(5)) // "ROLE_" 부분을 제거
                .collect(Collectors.toList());

        String token = jwtUtil.createJwt(user.getEmail(), roles, 60 * 60 * 24L * 1000); // 24 시간
        Cookie cookie = CookieUtil.createCookie("Authorization", token);
        HttpServletResponse response = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getResponse();
        if (response != null) {
            response.addCookie(cookie);
            log.info("JWT token added to response for user '{}'", user.getUsername());
        }
    }
}
