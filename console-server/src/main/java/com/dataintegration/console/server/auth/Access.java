package com.dataintegration.console.server.auth;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import java.util.Objects;

/**
 * Ownership rules (docs/design.md §2): everyone can see everything, but only the creator of a
 * project, datasource or job, or an administrator, may change, delete or run it. Runs and
 * schedules belong to their job. Only called from user-facing entry points; scheduler threads
 * have no user and never go through here.
 */
public final class Access {

    private Access() {}

    public static boolean isAdmin() {
        return CurrentUser.get().map(ConsoleUser::admin).orElse(false);
    }

    /** Whether the current user may change something created by {@code ownerId}. */
    public static boolean canEdit(String ownerId) {
        return CurrentUser.get().map(u -> u.admin() || Objects.equals(u.id(), ownerId)).orElse(false);
    }

    /** @param what what is being changed, for the message, e.g. "项目 demo" */
    public static void requireOwner(String ownerId, String what) {
        if (!canEdit(ownerId)) {
            throw new ConsoleException(ErrorCodes.FORBIDDEN, what + " 不是你创建的，只能查看；需要修改请联系创建人或管理员");
        }
    }

    public static void requireAdmin() {
        if (!isAdmin()) {
            throw new ConsoleException(ErrorCodes.FORBIDDEN, "只有管理员可以执行这个操作");
        }
    }
}
