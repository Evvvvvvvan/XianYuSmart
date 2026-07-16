package com.xianyusmart.controller;

import com.xianyusmart.annotation.NoAuth;
import com.xianyusmart.entity.MerchantShortLink;
import com.xianyusmart.mapper.MerchantShortLinkMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 短链跳转接口
 */
@NoAuth
@RestController
public class ShortLinkController {

    private final MerchantShortLinkMapper shortLinkMapper;

    public ShortLinkController(MerchantShortLinkMapper shortLinkMapper) {
        this.shortLinkMapper = shortLinkMapper;
    }

    @GetMapping("/s/{token}")
    public void redirect(@PathVariable String token, HttpServletResponse response) throws Exception {
        MerchantShortLink shortLink = shortLinkMapper.selectByToken(token);
        if (shortLink == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        URI uri = URI.create(shortLink.getTargetUrl());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        shortLinkMapper.incrementClicks(shortLink.getId());
        response.sendRedirect(shortLink.getTargetUrl());
    }
}
