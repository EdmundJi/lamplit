package com.betterself.growth.town.companion.application;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
public interface WorldStore {
    CompanionWorld read(long userId);
    CompanionWorld update(long userId, Supplier<CompanionWorld> initial, UnaryOperator<CompanionWorld> operation);
    boolean ownsTask(long userId,String taskId);
    String timezone(long userId);
}
