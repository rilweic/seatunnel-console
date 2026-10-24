package com.dataintegration.console.server.user;

import com.dataintegration.console.server.persistence.entity.SysUser;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Turns user ids from created_by columns into names for display. */
@Component
public class UserDirectory {

    private final SysUserMapper userMapper;

    public UserDirectory(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** Display name (or username) per id; ids without a user are left out. */
    public Map<String, String> names(Collection<String> ids) {
        var distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByIds(distinct).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u.getDisplayName() == null || u.getDisplayName().isBlank()
                        ? u.getUsername()
                        : u.getDisplayName()));
    }

    public String name(String id) {
        return id == null ? null : names(java.util.List.of(id)).get(id);
    }
}
