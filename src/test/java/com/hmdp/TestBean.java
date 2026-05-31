package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.dto.UserDTO;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class TestBean {
    @Test
    public void testMap() {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(1L);
        userDTO.setNickName("user");
        userDTO.setIcon(null);

        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue != null ? fieldValue.toString() : fieldValue));

        System.out.println("USERMAP: " + userMap);
    }
}
