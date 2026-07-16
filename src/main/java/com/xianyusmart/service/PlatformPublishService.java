package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.xianyusmart.config.PlaywrightManager;
import com.xianyusmart.entity.MerchantResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商品发布和删除浏览器执行器
 */
@Slf4j
@Service
public class PlatformPublishService {

    private static final Pattern GOODS_ID_PATTERN = Pattern.compile("(?:id=|/item/)(\\d{8,})");

    private final PlaywrightManager playwrightManager;
    private final AccountService accountService;
    private final ObjectMapper objectMapper;

    public PlatformPublishService(PlaywrightManager playwrightManager,
                                  AccountService accountService,
                                  ObjectMapper objectMapper) {
        this.playwrightManager = playwrightManager;
        this.accountService = accountService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> publish(MerchantResource material, Long accountId) {
        return publish(material, accountId, Map.of());
    }

    public Map<String, Object> publish(MerchantResource material, Long accountId, Map<String, Object> address) {
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        Map<String, Object> data = readData(material.getDataJson());
        List<Path> imagePaths = new ArrayList<>();
        Path tempDir = null;
        try (BrowserContext context = playwrightManager.createContext()) {
            addCookies(context, cookieText);
            Page page = context.newPage();
            page.navigate("https://www.goofish.com/publish",
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.waitForTimeout(2000);
            ensureLoggedIn(page);

            List<String> images = extractImages(data.get("images"));
            if (images.isEmpty()) {
                throw new IllegalArgumentException("素材至少需要一张商品图片");
            }
            tempDir = Files.createTempDirectory("xianyusmart-publish-");
            for (int i = 0; i < Math.min(images.size(), 9); i++) {
                imagePaths.add(downloadImage(images.get(i), tempDir, i));
            }
            Locator fileInput = page.locator("input[type=file]").first();
            if (fileInput.count() == 0) {
                throw new IllegalStateException("发布页面未找到图片上传控件");
            }
            fileInput.setInputFiles(imagePaths.toArray(Path[]::new));
            page.waitForTimeout(2500);

            String description = text(data.get("description"));
            if (description.isBlank()) {
                description = material.getName();
            }
            Locator descriptionInput = page.locator("textarea").first();
            if (descriptionInput.count() > 0) {
                descriptionInput.fill(description);
            }
            fillFirst(page, "input[placeholder*='价格'],input[placeholder*='售价']",
                    material.getAmount() == null ? "" : material.getAmount().stripTrailingZeros().toPlainString());
            String city = text(address.get("city"));
            String detail = text(address.get("detail"));
            fillFirst(page, "input[placeholder*='所在地'],input[placeholder*='发货地'],input[placeholder*='地址']",
                    (city + " " + detail).trim());

            Locator publishButton = page.locator("button:has-text(\"发布\")").last();
            if (publishButton.count() == 0) {
                throw new IllegalStateException("发布页面未找到发布按钮");
            }
            publishButton.click();
            page.waitForTimeout(4000);
            String bodyText = page.locator("body").innerText();
            if (bodyText.contains("滑块") || bodyText.contains("异常流量") || bodyText.contains("验证")) {
                throw new IllegalStateException("发布触发平台验证，请更新账号登录状态后重试");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("url", page.url());
            Matcher matcher = GOODS_ID_PATTERN.matcher(page.url());
            if (matcher.find()) {
                result.put("itemId", matcher.group(1));
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("商品发布失败: " + e.getMessage(), e);
        } finally {
            for (Path imagePath : imagePaths) {
                try {
                    Files.deleteIfExists(imagePath);
                } catch (Exception ignored) {
                }
            }
            if (tempDir != null) {
                try {
                    Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public Map<String, Object> delete(Long accountId, String goodsId) {
        String cookieText = accountService.getCookieByAccountId(accountId);
        if (cookieText == null || cookieText.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        try (BrowserContext context = playwrightManager.createContext()) {
            addCookies(context, cookieText);
            Page page = context.newPage();
            page.navigate("https://www.goofish.com/item?id=" + goodsId,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.waitForTimeout(2000);
            ensureLoggedIn(page);
            Locator deleteButton = page.locator("button:has-text(\"删除\"),button:has-text(\"下架\")").first();
            if (deleteButton.count() == 0) {
                throw new IllegalStateException("商品页面未找到删除或下架操作");
            }
            deleteButton.click();
            Locator confirmButton = page.locator("button:has-text(\"确定\"),button:has-text(\"确认\")").last();
            if (confirmButton.count() > 0) {
                confirmButton.click();
            }
            page.waitForTimeout(2000);
            return Map.of("success", true, "itemId", goodsId);
        }
    }

    public Map<String, Object> collect(String sourceUrl, Long accountId) {
        validatePlatformUrl(sourceUrl);
        try (BrowserContext context = playwrightManager.createContext()) {
            if (accountId != null) {
                String cookieText = accountService.getCookieByAccountId(accountId);
                if (cookieText != null && !cookieText.isBlank()) {
                    addCookies(context, cookieText);
                }
            }
            Page page = context.newPage();
            page.navigate(sourceUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.waitForTimeout(2000);
            String bodyText = page.locator("body").innerText();
            if (bodyText.contains("异常流量") || bodyText.contains("滑块验证")) {
                throw new IllegalStateException("商品采集触发平台验证，请稍后重试");
            }
            Map<String, Object> result = new HashMap<>();
            String title = firstText(page, "h1,[class*='title']");
            if (title.isBlank()) {
                title = attribute(page, "meta[property='og:title']", "content");
            }
            String description = attribute(page, "meta[name='description']", "content");
            LinkedHashSet<String> images = new LinkedHashSet<>();
            String mainImage = attribute(page, "meta[property='og:image']", "content");
            if (!mainImage.isBlank()) {
                images.add(mainImage);
            }
            for (Locator image : page.locator("img").all()) {
                String src = image.getAttribute("src");
                if (src != null && src.startsWith("https://")) {
                    images.add(src);
                }
                if (images.size() >= 9) {
                    break;
                }
            }
            result.put("title", title.isBlank() ? "采集商品" : title);
            result.put("description", description);
            result.put("images", new ArrayList<>(images));
            result.put("sourceUrl", page.url());
            Matcher matcher = GOODS_ID_PATTERN.matcher(page.url());
            if (matcher.find()) {
                result.put("itemId", matcher.group(1));
            }
            return result;
        }
    }

    public List<Map<String, Object>> search(String keyword, Long accountId, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String url = "https://www.goofish.com/search?q=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8);
        try (BrowserContext context = playwrightManager.createContext()) {
            if (accountId != null) {
                String cookieText = accountService.getCookieByAccountId(accountId);
                if (cookieText != null && !cookieText.isBlank()) {
                    addCookies(context, cookieText);
                }
            }
            Page page = context.newPage();
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
            page.waitForTimeout(2500);
            String bodyText = page.locator("body").innerText();
            if (bodyText.contains("异常流量") || bodyText.contains("滑块验证")) {
                throw new IllegalStateException("选品采集触发平台验证，请稍后重试");
            }
            List<Map<String, Object>> items = new ArrayList<>();
            LinkedHashSet<String> visited = new LinkedHashSet<>();
            for (Locator anchor : page.locator("a[href*='/item'],a[href*='id=']").all()) {
                String href = anchor.getAttribute("href");
                if (href == null || !visited.add(href)) {
                    continue;
                }
                String itemUrl = href.startsWith("http") ? href : "https://www.goofish.com" + href;
                Matcher matcher = GOODS_ID_PATTERN.matcher(itemUrl);
                if (!matcher.find()) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("itemId", matcher.group(1));
                item.put("sourceUrl", itemUrl);
                String title = anchor.innerText().trim();
                item.put("title", title.isBlank() ? keyword.trim() + "-" + matcher.group(1) : title);
                Locator image = anchor.locator("img").first();
                if (image.count() > 0) {
                    String src = image.getAttribute("src");
                    if (src != null && src.startsWith("https://")) {
                        item.put("images", List.of(src));
                    }
                }
                items.add(item);
                if (items.size() >= Math.max(1, Math.min(limit, 50))) {
                    break;
                }
            }
            return items;
        }
    }

    private void ensureLoggedIn(Page page) {
        String url = page.url();
        String bodyText = page.locator("body").innerText();
        if (url.contains("login") || bodyText.contains("请先登录") || bodyText.contains("扫码登录")) {
            throw new IllegalStateException("账号Cookie已失效");
        }
    }

    private void validatePlatformUrl(String sourceUrl) {
        URI uri = URI.create(sourceUrl);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equals("goofish.com") || host.endsWith(".goofish.com"))) {
            throw new IllegalArgumentException("仅支持HTTPS闲鱼商品地址");
        }
    }

    private String firstText(Page page, String selector) {
        Locator locator = page.locator(selector).first();
        return locator.count() == 0 ? "" : locator.innerText().trim();
    }

    private String attribute(Page page, String selector, String name) {
        Locator locator = page.locator(selector).first();
        if (locator.count() == 0) {
            return "";
        }
        String value = locator.getAttribute(name);
        return value == null ? "" : value.trim();
    }

    private void addCookies(BrowserContext context, String cookieText) {
        List<Cookie> cookies = new ArrayList<>();
        for (String part : cookieText.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank()) {
                cookies.add(new Cookie(pair[0].trim(), pair[1].trim())
                        .setDomain(".goofish.com").setPath("/").setSecure(true));
            }
        }
        context.addCookies(cookies);
    }

    private Path downloadImage(String imageUrl, Path tempDir, int index) throws Exception {
        URI uri = URI.create(imageUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("商品图片必须使用HTTPS地址");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()) {
                throw new IllegalArgumentException("商品图片地址不可访问内网");
            }
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "XianYuSmart/1.0");
        if (connection.getResponseCode() != 200) {
            throw new IllegalStateException("商品图片下载失败");
        }
        String contentType = connection.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new IllegalArgumentException("商品图片地址未返回图片内容");
        }
        long contentLength = connection.getContentLengthLong();
        if (contentLength > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("单张商品图片不能超过10MB");
        }
        Path target = tempDir.resolve("image-" + index + ".jpg");
        try (var input = connection.getInputStream(); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 10 * 1024 * 1024) {
                    throw new IllegalArgumentException("单张商品图片不能超过10MB");
                }
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
        return target;
    }

    private void fillFirst(Page page, String selector, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Locator input = page.locator(selector).first();
        if (input.count() > 0) {
            input.fill(value);
        }
    }

    private List<String> extractImages(Object images) {
        if (images instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList();
        }
        if (images instanceof String value && !value.isBlank()) {
            return List.of(value.split(",")).stream().map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        return List.of();
    }

    private Map<String, Object> readData(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(dataJson, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("素材数据格式错误", e);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
