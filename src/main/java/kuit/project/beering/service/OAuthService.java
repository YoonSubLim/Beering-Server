package kuit.project.beering.service;

import jakarta.persistence.EntityNotFoundException;
import kuit.project.beering.domain.Member;
import kuit.project.beering.domain.OAuth;
import kuit.project.beering.domain.OAuthType;
import kuit.project.beering.dto.common.OAuthMemberInfo;
import kuit.project.beering.dto.request.auth.OAuthSignupRequest;
import kuit.project.beering.dto.request.member.MemberSignupRequest;
import kuit.project.beering.dto.response.member.MemberLoginResponse;
import kuit.project.beering.redis.RefreshToken;
import kuit.project.beering.repository.MemberRepository;
import kuit.project.beering.repository.OAuthRepository;
import kuit.project.beering.repository.RefreshTokenRepository;
import kuit.project.beering.security.auth.oauth.helper.OAuthHelper;
import kuit.project.beering.security.jwt.JwtInfo;
import kuit.project.beering.security.jwt.JwtTokenProviderResolver;
import kuit.project.beering.security.jwt.OAuthTokenInfo;
import kuit.project.beering.security.jwt.jwtTokenProvider.JwtTokenProvider;
import kuit.project.beering.util.exception.SignupNotCompletedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OAuthService {

    private final MemberService memberService;
    private final MemberRepository memberRepository;
    private final OAuthRepository oauthRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProviderResolver jwtTokenProviderResolver;

    @Transactional
    public MemberLoginResponse oauth(String code, OAuthHelper oAuthHelper) {
        // 1. "인가 코드"로 "액세스 토큰" 요청
        OAuthTokenInfo oauthTokenInfo = oAuthHelper.createToken(code);

        // 2. 토큰으로 oauth account API 호출
        OAuthMemberInfo OAuthMemberInfo = oAuthHelper.getAccount(oauthTokenInfo.getAccessToken());

        // 3. oauth_id 로 회원가입 처리
        Member oauthMember = registerKakaoUserIfNeed(OAuthMemberInfo, oauthTokenInfo, oAuthHelper.getOauthType());

        return MemberLoginResponse.builder()
                .memberId(oauthMember.getId())
                .jwtInfo(JwtInfo.builder()
                        .accessToken(oauthTokenInfo.getIdToken())
                        .refreshToken(oauthTokenInfo.getRefreshToken())
                        .build())
                .build();
    }

    @Transactional
    public MemberLoginResponse signup(OAuthSignupRequest request, OAuthHelper oauthHelper) {

        OAuth oauth = oauthRepository.findBySub(request.getSub())
                .orElseThrow(EntityNotFoundException::new);

        OAuthMemberInfo oauthAccountInfo = oauthHelper.getAccount(oauth.getAccessToken());

        String email = oauthAccountInfo.getEmail();
        String nickname = oauthAccountInfo.getNickname();

        // 회원가입 처리
        memberService.signup(new MemberSignupRequest(email, UUID.randomUUID().toString(), nickname, request.getAgreements()));
        Member member = memberRepository.findByUsername(email).orElseThrow(EntityNotFoundException::new);
        member.createOauthAssociation(oauth);

        // 토큰 발행
        OAuthTokenInfo oauthTokenInfo = oauthHelper.reissueToken(oauth.getRefreshToken());

        String idToken = oauthTokenInfo.getIdToken();
        String accessToken = oauthTokenInfo.getAccessToken();
        String refreshToken = oauthTokenInfo.getRefreshToken();

        // 값 변경
        oauth.tokenReissue(accessToken, refreshToken);

        refreshTokenRepository.save(new RefreshToken(String.valueOf(member.getId()), refreshToken));

        return MemberLoginResponse.builder()
                .memberId(member.getId())
                .jwtInfo(JwtInfo.builder().accessToken(idToken).refreshToken(refreshToken).build())
                .build();
    }

    // 3. sub 로 회원가입 처리
    private Member registerKakaoUserIfNeed(OAuthMemberInfo OAuthMemberInfo, OAuthTokenInfo oauthTokenInfo, OAuthType oauthType) {
        JwtTokenProvider tokenProvider = jwtTokenProviderResolver.getProvider(oauthTokenInfo.getIdToken());
        // DB 에 중복된 email이 있는지 확인
        // 없으면 닉네임 및 약관 요청 있으면 강제 로그인..
        Member member = memberRepository.findByUsername(OAuthMemberInfo.getEmail())
                .orElseThrow(() -> {
                    OAuth oauth = oauthRepository.save(OAuth.createOauth(tokenProvider.parseSub(oauthTokenInfo.getIdToken()),
                            OAuthType.KAKAO, oauthTokenInfo.getAccessToken(), oauthTokenInfo.getRefreshToken()));
                    throw new SignupNotCompletedException(oauth.getSub(), oauthType);
                });

        refreshTokenRepository.save(new RefreshToken(String.valueOf(member.getId()), oauthTokenInfo.getRefreshToken()));

        return member;
    }

}