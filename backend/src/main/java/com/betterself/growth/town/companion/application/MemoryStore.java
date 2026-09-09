package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.CompanionWorld.Memory;

import java.util.List;

/**
 * Where residents' memories actually live. Files are the authority (see docs/02-modules.md "记忆存在
 * 哪"): one directory per resident, one file per remembered fact, so each person's memories are
 * physically separated rather than sharing one JSON column, and a human can read, edit or delete
 * them with an ordinary text editor.
 *
 * <p>The only read here is scoped to one owner. That is the point, and it is the storage-layer half
 * of docs/04-decisions.md 「隔离落在存储层」: there is deliberately no "give me every memory in this
 * world" call, because a shared pool plus application-level filtering is the shape that leaks. A
 * caller that wants the whole town's memories has to ask resident by resident, naming each one.
 *
 * <p>Writes are append-or-replace by memory id. Nothing is ever deleted by the simulation - an
 * outdated belief is superseded, not removed (04 「旧记忆不删除，只标记为已被取代」), which a later
 * batch expresses as fields on the memory itself. {@link #deleteUser} is the one true deletion, and
 * it exists for account closure: a user's memories are their data, and 02-modules is explicit that
 * closing an account has to take the memory directory with it.
 */
public interface MemoryStore {
    /** One resident's own memories, oldest first. {@code ownerId} is required - see the class doc. */
    List<Memory> byOwner(long userId, String ownerId);

    /** Persist these memories for this world, keyed by their own {@code ownerId}/{@code id}. Existing
     * ids are overwritten in place; ids not mentioned here are left alone, never pruned. */
    void save(long userId, List<Memory> memories);

    /** Remove everything this user's residents ever remembered - files and index rows both. Called
     * from account deletion; nothing in the simulation may call it. */
    void deleteUser(long userId);
}
