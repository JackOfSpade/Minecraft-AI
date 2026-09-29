package dev.spawnbotswrapper.inhabitants.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.Registries;

/**
 * Tab completion for the command tree. Each provider is best effort and cheap: a failure yields no
 * suggestions instead of an error, because completion runs on every keystroke and must never disturb the
 * player. The candidate selection is separate from the Brigadier wiring so it can be tested on plain data.
 */
final class Suggesters {
    private Suggesters() {
    }

    // ---------------------------------------------------------------- Brigadier providers

    /** Names of inhabitants that live in processed structures near the sender. */
    static SuggestionProvider<CommandSourceStack> botNames(Supplier<CommandServices> services,
                                                              Function<CommandSourceStack, Sender> senders) {
        return strings(ctx -> {
            CommandServices s = services.get();
            if (s == null) {
                return List.of();
            }
            Sender at = senders.apply(ctx.getSource());
            return botNames(s.population().nearby(at.dimensionId(), at.chunkX(), at.chunkZ(),
                    CommandArgs.SUGGEST_RADIUS), CommandArgs.MAX_SUGGESTIONS);
        });
    }

    /** Every registered structure id (vanilla and modded), straight from the registry. */
    static SuggestionProvider<CommandSourceStack> structureIds() {
        return (ctx, builder) -> {
            try {
                return SharedSuggestionProvider.suggestResource(
                        ctx.getSource().registryAccess().lookupOrThrow(Registries.STRUCTURE).keySet(), builder);
            } catch (RuntimeException e) {
                return builder.buildFuture();
            }
        };
    }

    /** Start-chunk X of processed structures with the id already typed, else the sender's own chunk. */
    static SuggestionProvider<CommandSourceStack> chunkX(Supplier<CommandServices> services,
                                                            Function<CommandSourceStack, Sender> senders) {
        return strings(ctx -> {
            CommandServices s = services.get();
            if (s == null) {
                return List.of();
            }
            Sender at = senders.apply(ctx.getSource());
            String id = IdentifierArgument.getId(ctx, CommandArgs.ARG_STRUCTURE).toString();
            return chunkXs(s.population().nearby(at.dimensionId(), at.chunkX(), at.chunkZ(),
                    CommandArgs.SUGGEST_RADIUS), id, at.chunkX());
        });
    }

    /** Start-chunk Z of processed structures with the id and X already typed, else the sender's own chunk. */
    static SuggestionProvider<CommandSourceStack> chunkZ(Supplier<CommandServices> services,
                                                            Function<CommandSourceStack, Sender> senders) {
        return strings(ctx -> {
            CommandServices s = services.get();
            if (s == null) {
                return List.of();
            }
            Sender at = senders.apply(ctx.getSource());
            String id = IdentifierArgument.getId(ctx, CommandArgs.ARG_STRUCTURE).toString();
            int x = IntegerArgumentType.getInteger(ctx, CommandArgs.ARG_CHUNK_X);
            return chunkZs(s.population().nearby(at.dimensionId(), at.chunkX(), at.chunkZ(),
                    CommandArgs.SUGGEST_RADIUS), id, x, at.chunkZ());
        });
    }

    static SuggestionProvider<CommandSourceStack> categories() {
        return strings(ctx -> categoryWords());
    }

    /** Wraps a candidate function: vanilla-style matching, and any failure means "no suggestions". */
    static <S> SuggestionProvider<S> strings(Function<CommandContext<S>, ? extends Collection<String>> candidates) {
        return (ctx, builder) -> {
            Collection<String> found;
            try {
                found = candidates.apply(ctx);
            } catch (RuntimeException | LinkageError e) {
                found = List.of();
            }
            return SharedSuggestionProvider.suggest(found, builder);
        };
    }

    // ---------------------------------------------------------------- candidate selection (pure)

    /** Distinct bot names, in the order given (nearest structure first), at most {@code cap}. */
    static List<String> botNames(List<Map.Entry<StructureKey, StructureRecord>> entries, int cap) {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<StructureKey, StructureRecord> e : entries) {
            if (e.getValue().bots == null) {
                continue;
            }
            for (BotRecord b : e.getValue().bots) {
                if (b.name != null && !b.name.isBlank()) {
                    names.add(b.name);
                    if (names.size() >= cap) {
                        return new ArrayList<>(names);
                    }
                }
            }
        }
        return new ArrayList<>(names);
    }

    /** Chunk X values of structures with {@code structureId}; {@code fallback} when there are none. */
    static List<String> chunkXs(List<Map.Entry<StructureKey, StructureRecord>> entries, String structureId,
                                int fallback) {
        Set<String> xs = new LinkedHashSet<>();
        for (Map.Entry<StructureKey, StructureRecord> e : entries) {
            if (e.getKey().structureId().equals(structureId)) {
                xs.add(String.valueOf(e.getKey().chunkX()));
            }
        }
        if (xs.isEmpty()) {
            xs.add(String.valueOf(fallback));
        }
        return new ArrayList<>(xs);
    }

    /** Chunk Z values of structures with {@code structureId} at chunk X {@code x}; {@code fallback} if none. */
    static List<String> chunkZs(List<Map.Entry<StructureKey, StructureRecord>> entries, String structureId, int x,
                                int fallback) {
        Set<String> zs = new LinkedHashSet<>();
        for (Map.Entry<StructureKey, StructureRecord> e : entries) {
            StructureKey k = e.getKey();
            if (k.structureId().equals(structureId) && k.chunkX() == x) {
                zs.add(String.valueOf(k.chunkZ()));
            }
        }
        if (zs.isEmpty()) {
            zs.add(String.valueOf(fallback));
        }
        return new ArrayList<>(zs);
    }

    static List<String> categoryWords() {
        List<String> words = new ArrayList<>();
        for (Category c : Category.values()) {
            words.add(c.name().toLowerCase(Locale.ROOT));
        }
        return words;
    }
}
