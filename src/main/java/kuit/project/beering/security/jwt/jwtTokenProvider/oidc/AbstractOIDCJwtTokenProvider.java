package kuit.project.beering.security.jwt.jwtTokenProvider.oidc;

import kuit.project.beering.domain.Member;
import kuit.project.beering.repository.MemberRepository;
import kuit.project.beering.repository.OAuthRepository;
import kuit.project.beering.security.auth.AuthMember;
import kuit.project.beering.security.auth.oauth.helper.OAuthHelper;
import kuit.project.beering.security.jwt.JwtInfo;
import kuit.project.beering.security.jwt.JwtParser;
import kuit.project.beering.security.jwt.OAuthTokenInfo;
import kuit.project.beering.security.jwt.jwtTokenProvider.AbstractJwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

@Slf4j
public abstract class AbstractOIDCJwtTokenProvider extends AbstractJwtTokenProvider {

    protected final OAuthHelper oauthHelper;
    protected final MemberRepository memberRepository;
    private final OAuthRepository oauthRepository;

    public AbstractOIDCJwtTokenProvider(
            OAuthHelper oauthHelper,
            JwtParser jwtParser,
            MemberRepository memberRepository,
            OAuthRepository oauthRepository) {
        super(jwtParser);
        this.oauthHelper = oauthHelper;
        this.memberRepository = memberRepository;
        this.oauthRepository = oauthRepository;
    }

    @Override
    public boolean validateToken(String token) {
        return oauthHelper.validateToken(token);
    }

    /**
     * @param token
     * @return Authentication
     * @Brief token 에서 인증(Authentication) 파싱
     */
    @Override
    public Authentication getAuthentication(String token) {

        Member member = memberRepository.findByOAuthSubAndOAuthType(parseSub(token), oauthHelper.getOauthType())
                .orElseThrow(IllegalArgumentException::new);

        UserDetails authMember =
                AuthMember.builder()
                        .id(member.getId())
                        .username(member.getUsername())
                        .password("")
                        .build();

        return new UsernamePasswordAuthenticationToken(authMember, "", new ArrayList<>());
    }

    @Override
    public String validateRefreshToken(String refreshToken) {
        return String.valueOf(
                memberRepository.findByOauthRefreshTokenAndOauthType(
                        refreshToken, oauthHelper.getOauthType())
                .orElseThrow(IllegalArgumentException::new).getId());
    }

    @Override
    @Transactional
    public JwtInfo reissueJwtToken(String refreshToken) {

        OAuthTokenInfo oauthTokenInfo = oauthHelper.reissueToken(refreshToken);

        oauthTokenInfo.setRefreshToken(oauthTokenInfo.getRefreshToken());

        oauthRepository.findBySub(parseSub(oauthTokenInfo.getIdToken()))
                .orElseThrow(IllegalArgumentException::new)
                .tokenReissue(oauthTokenInfo.getAccessToken(), oauthTokenInfo.getRefreshToken());

        return JwtInfo.builder()
                .accessToken(oauthTokenInfo.getIdToken())
                .refreshToken(oauthTokenInfo.getRefreshToken())
                .build();
    }

    /**
     * @Brief 로그인 시 발급
     */
    public OAuthTokenInfo createToken(String code) {
        return oauthHelper.createToken(code);
    }

    public String parseSub(String token) {
        return jwtParser.parseClaimsField(token, "sub", String.class);
    }

}
