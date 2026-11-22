package com.my.food_ad_check.controller;

import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductNotificationHttpTests {
    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired UserRepository users;
    @Autowired ProductFunctionChangeRepository changes;
    @Autowired ProductChangeNotificationRepository notifications;

    @Test
    void notificationEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/1/read")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inspections/1/reinspections").contentType("application/json")
                .content("{\"adText\":\"수정\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanReadNotificationButOtherUsersCannot() throws Exception {
        String id = "http-" + UUID.randomUUID();
        var product = new Product();
        product.setPrdlstReportNo(id);
        product.setPrdlstNm("테스트 제품");
        product.setProductType("건강기능식품");
        products.saveAndFlush(product);
        var owner = users.saveAndFlush(new User(id + "@test.example", "fixture", null));
        var other = users.saveAndFlush(new User(id + "other@test.example", "fixture", null));
        var change = changes.saveAndFlush(new ProductFunctionChange(id, "이전", "현재", LocalDateTime.now()));
        var notice = notifications.saveAndFlush(new ProductChangeNotification(owner.getId(), change));
        mvc.perform(get("/api/notifications").with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].reportNo").value(id))
                .andExpect(jsonPath("$.items[0].previousSource").value("이전"));
        mvc.perform(post("/api/notifications/" + notice.getId() + "/read")
                .with(jwt().jwt(j -> j.subject(other.getId().toString())))).andExpect(status().isNotFound());
        mvc.perform(post("/api/notifications/" + notice.getId() + "/read")
                .with(jwt().jwt(j -> j.subject(owner.getId().toString())))).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications?unreadOnly=true")
                .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
