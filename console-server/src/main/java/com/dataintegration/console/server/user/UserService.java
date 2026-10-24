package com.dataintegration.console.server.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.auth.AccountChanges;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.auth.ConsoleUser;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.auth.LoginAttempts;
import com.dataintegration.console.server.auth.PasswordPolicy;
import com.dataintegration.console.server.persistence.entity.DatasourceEntity;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.Project;
import com.dataintegration.console.server.persistence.entity.SysUser;
import com.dataintegration.console.server.persistence.mapper.DatasourceMapper;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectMapper;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Account management for administrators (docs/design.md §2). Users are disabled, never deleted,
 * because projects, jobs and runs keep pointing at them. At least one enabled administrator must
 * remain.
 */
@Service
public class UserService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{2,64}");

    public record UserView(
            String id,
            String username,
            String displayName,
            String role,
            boolean enabled,
            boolean mustChangePassword,
            String lastLoginAt,
            String createdAt,
            long projects,
            long datasources,
            long jobs) {}

    /** What a transfer moved. */
    public record Transferred(int projects, int datasources, int jobs) {}

    private final SysUserMapper userMapper;
    private final ProjectMapper projectMapper;
    private final DatasourceMapper datasourceMapper;
    private final JobDefinitionMapper jobMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttempts loginAttempts;
    private final AccountChanges accountChanges;

    public UserService(
            SysUserMapper userMapper,
            ProjectMapper projectMapper,
            DatasourceMapper datasourceMapper,
            JobDefinitionMapper jobMapper,
            PasswordEncoder passwordEncoder,
            LoginAttempts loginAttempts,
            AccountChanges accountChanges) {
        this.userMapper = userMapper;
        this.projectMapper = projectMapper;
        this.datasourceMapper = datasourceMapper;
        this.jobMapper = jobMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        this.accountChanges = accountChanges;
    }

    /** Everyone may list users (for owner filters); only administrators manage them. */
    public List<UserView> list() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getCreatedAt)).stream()
                .map(this::view)
                .toList();
    }

    public UserView create(String username, String displayName, String role, String password) {
        Access.requireAdmin();
        if (username == null || !USERNAME.matcher(username.trim()).matches()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "用户名为 2～64 位字母、数字、_ . -");
        }
        requireRole(role);
        PasswordPolicy.check(password);
        SysUser user = new SysUser();
        user.setUsername(username.trim());
        user.setDisplayName(blankToNull(displayName));
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        // The admin knows this password, so the user has to replace it.
        user.setMustChangePassword(true);
        userMapper.insert(user);
        return view(userMapper.selectById(user.getId()));
    }

    @Transactional
    public UserView update(String id, String displayName, String role, boolean enabled) {
        Access.requireAdmin();
        SysUser user = require(id);
        requireRole(role);
        boolean losesAdmin = ConsoleUser.ROLE_ADMIN.equals(user.getRole())
                && Boolean.TRUE.equals(user.getEnabled())
                && (!ConsoleUser.ROLE_ADMIN.equals(role) || !enabled);
        if (losesAdmin && enabledAdmins() <= 1) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "至少要保留一个启用状态的管理员");
        }
        if (!enabled && id.equals(CurrentUser.idOrNull())) {
            throw new ConsoleException(ErrorCodes.CONFLICT, "不能禁用自己");
        }
        userMapper.update(
                null,
                new LambdaUpdateWrapper<SysUser>()
                        .set(SysUser::getDisplayName, blankToNull(displayName))
                        .set(SysUser::getRole, role)
                        .set(SysUser::getEnabled, enabled)
                        .eq(SysUser::getId, id));
        accountChanges.changed(id);
        return view(require(id));
    }

    /** Sets a new password the admin hands over; the user must change it at the next login. */
    public UserView resetPassword(String id, String password) {
        Access.requireAdmin();
        SysUser user = require(id);
        PasswordPolicy.check(password);
        SysUser update = new SysUser();
        update.setId(id);
        update.setPasswordHash(passwordEncoder.encode(password));
        update.setMustChangePassword(true);
        update.setPasswordChangedAt(LocalDateTime.now());
        userMapper.updateById(update);
        loginAttempts.clear(user.getUsername());
        accountChanges.changed(id);
        return view(require(id));
    }

    /**
     * Moves everything a user owns (projects, datasources, jobs; runs and schedules follow their
     * jobs) to another user, e.g. when someone leaves. All or nothing, never item by item.
     */
    @Transactional
    public Transferred transfer(String fromId, String toId) {
        Access.requireAdmin();
        require(fromId);
        SysUser to = require(toId);
        if (fromId.equals(toId)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "不能转给同一个用户");
        }
        if (!Boolean.TRUE.equals(to.getEnabled())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "不能转给已禁用的用户");
        }
        int projects = projectMapper.update(null,
                new LambdaUpdateWrapper<Project>().set(Project::getCreatedBy, toId).eq(Project::getCreatedBy, fromId));
        int datasources = datasourceMapper.update(null,
                new LambdaUpdateWrapper<DatasourceEntity>().set(DatasourceEntity::getCreatedBy, toId).eq(DatasourceEntity::getCreatedBy, fromId));
        int jobs = jobMapper.update(null,
                new LambdaUpdateWrapper<JobDefinition>().set(JobDefinition::getCreatedBy, toId).eq(JobDefinition::getCreatedBy, fromId));
        return new Transferred(projects, datasources, jobs);
    }

    /**
     * Locks the enabled administrators' rows until the transaction ends, so two concurrent demotions
     * cannot both see "another admin is left" and remove the last two together.
     */
    private long enabledAdmins() {
        return userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, ConsoleUser.ROLE_ADMIN)
                .eq(SysUser::getEnabled, true)
                .last("for update"));
    }

    private SysUser require(String id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "user not found: " + id);
        }
        return user;
    }

    private static void requireRole(String role) {
        if (!ConsoleUser.ROLE_ADMIN.equals(role) && !ConsoleUser.ROLE_USER.equals(role)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "role must be ADMIN or USER");
        }
    }

    private UserView view(SysUser u) {
        return new UserView(
                u.getId(),
                u.getUsername(),
                u.getDisplayName(),
                u.getRole(),
                Boolean.TRUE.equals(u.getEnabled()),
                Boolean.TRUE.equals(u.getMustChangePassword()),
                u.getLastLoginAt() == null ? null : u.getLastLoginAt().toString(),
                u.getCreatedAt() == null ? null : u.getCreatedAt().toString(),
                projectMapper.selectCount(new LambdaQueryWrapper<Project>().eq(Project::getCreatedBy, u.getId())),
                datasourceMapper.selectCount(new LambdaQueryWrapper<DatasourceEntity>().eq(DatasourceEntity::getCreatedBy, u.getId())),
                jobMapper.selectCount(new LambdaQueryWrapper<JobDefinition>().eq(JobDefinition::getCreatedBy, u.getId())));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
