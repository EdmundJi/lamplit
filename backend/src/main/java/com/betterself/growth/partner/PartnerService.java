package com.betterself.growth.partner;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PartnerService {

    private static final Set<String> SPECIES = Set.of("CAT", "DOG", "HAMSTER", "SNAKE", "RABBIT", "BIRD", "TURTLE", "FOX");

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;

    public PartnerService(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public PartnerProfile profile(long userId) {
        ensureWallet(userId);
        ensureStarterPet(userId);
        return new PartnerProfile(wallet(userId), pets(userId), selectedPet(userId), shopItems(), templateLibraries());
    }

    @Transactional
    public PetView createPet(long userId, CreatePetCommand command) {
        if (command == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PET", "伙伴信息不能为空");
        }
        ensureWallet(userId);
        String species = species(command.speciesCode());
        String name = required(command.name(), "PET_NAME_REQUIRED", "伙伴名字不能为空", 40);
        String breed = required(command.breed(), "PET_BREED_REQUIRED", "品种不能为空", 60);
        String furColor = required(command.furColor(), "PET_COLOR_REQUIRED", "毛发颜色不能为空", 40);
        boolean firstPet = jdbc.queryForObject("select count(*) = 0 from partner_pet where user_id = ?", Boolean.class, userId);
        String publicId = ids.next();
        if (firstPet) {
            jdbc.update("update partner_pet set selected = 0 where user_id = ?", userId);
        }
        jdbc.update(
            """
                insert into partner_pet (public_id, user_id, species_code, name, breed, fur_color, selected)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
            publicId, userId, species, name, breed, furColor, firstPet
        );
        return pet(userId, publicId);
    }

    @Transactional
    public PetView updatePet(long userId, String petPublicId, UpdatePetCommand command) {
        PetRow current = petRow(userId, petPublicId);
        String name = command.name() == null ? current.name() : required(command.name(), "PET_NAME_REQUIRED", "伙伴名字不能为空", 40);
        String breed = command.breed() == null ? current.breed() : required(command.breed(), "PET_BREED_REQUIRED", "品种不能为空", 60);
        String furColor = command.furColor() == null ? current.furColor() : required(command.furColor(), "PET_COLOR_REQUIRED", "毛发颜色不能为空", 40);
        jdbc.update(
            "update partner_pet set name = ?, breed = ?, fur_color = ?, updated_at = UTC_TIMESTAMP(3) where id = ?",
            name, breed, furColor, current.id()
        );
        return pet(userId, petPublicId);
    }

    @Transactional
    public PetView selectPet(long userId, String petPublicId) {
        PetRow row = petRow(userId, petPublicId);
        jdbc.update("update partner_pet set selected = 0 where user_id = ?", userId);
        jdbc.update("update partner_pet set selected = 1, updated_at = UTC_TIMESTAMP(3) where id = ?", row.id());
        return pet(userId, petPublicId);
    }

    @Transactional
    public InteractionResult interact(long userId, String petPublicId) {
        PetRow row = petRow(userId, petPublicId);
        Instant now = clock.instant();
        LocalDate interactionDate = localDate(userId, now);
        int inserted = jdbc.update(
            """
                insert ignore into partner_interaction (
                    public_id, user_id, pet_id, interaction_date, affection_delta, interacted_at
                ) values (?, ?, ?, ?, 2, ?)
                """,
            ids.next(), userId, row.id(), java.sql.Date.valueOf(interactionDate), Timestamp.from(now)
        );
        boolean rewarded = inserted == 1;

        if (rewarded) {
            Affection next = addAffection(row.level(), row.affection(), 2);
            jdbc.update(
                "update partner_pet set level = ?, affection = ?, last_interacted_at = ?, updated_at = UTC_TIMESTAMP(3) where id = ?",
                next.level(), next.affection(), Timestamp.from(now), row.id()
            );
        } else {
            jdbc.update(
                "update partner_pet set last_interacted_at = ?, updated_at = UTC_TIMESTAMP(3) where id = ?",
                Timestamp.from(now), row.id()
            );
        }

        return new InteractionResult(pet(userId, petPublicId), rewarded ? 2 : 0, rewarded, interactionDate);
    }

    @Transactional
    public PurchaseResult purchase(long userId, PurchaseCommand command) {
        if (command == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PURCHASE", "购买请求无效");
        }
        ensureWallet(userId);
        PetRow pet = petRow(userId, command.petPublicId());
        ShopItemRow item = shopItem(command.itemPublicId());
        if (item.speciesCode() != null && !item.speciesCode().equals(pet.speciesCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHOP_ITEM_SPECIES_MISMATCH", "这个物品不适合当前伙伴");
        }
        WalletRow wallet = walletForUpdate(userId);
        if (wallet.coinBalance() < item.price()) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_COINS", "金币不足");
        }
        jdbc.update(
            "update user_wallet set coin_balance = coin_balance - ?, updated_at = UTC_TIMESTAMP(3) where user_id = ?",
            item.price(), userId
        );
        Affection next = addAffection(pet.level(), pet.affection(), item.affectionGain());
        jdbc.update(
            "update partner_pet set level = ?, affection = ?, updated_at = UTC_TIMESTAMP(3) where id = ?",
            next.level(), next.affection(), pet.id()
        );
        String purchasePublicId = ids.next();
        Instant now = clock.instant();
        jdbc.update(
            """
                insert into partner_purchase (public_id, user_id, pet_id, item_id, coin_delta, affection_delta, purchased_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
            purchasePublicId, userId, pet.id(), item.id(), -item.price(), item.affectionGain(), Timestamp.from(now)
        );
        return new PurchaseResult(purchasePublicId, wallet(userId), pet(userId, pet.publicId()), itemView(item), now);
    }

    @Transactional
    public int applyTaskCoinDelta(long userId, int experienceDelta) {
        if (experienceDelta == 0) {
            return 0;
        }
        ensureWallet(userId);
        WalletRow wallet = walletForUpdate(userId);
        int requested = experienceDelta > 0
            ? (int) Math.ceil(experienceDelta / 2.0)
            : -(int) Math.ceil(Math.abs(experienceDelta) / 2.0);
        int nextBalance = Math.max(0, Math.min(999999, wallet.coinBalance() + requested));
        int actual = nextBalance - wallet.coinBalance();
        int lifetimeDelta = Math.max(0, actual);
        jdbc.update(
            """
                update user_wallet
                set coin_balance = ?, lifetime_coins = least(999999, lifetime_coins + ?), updated_at = UTC_TIMESTAMP(3)
                where user_id = ?
                """,
            nextBalance, lifetimeDelta, userId
        );
        return actual;
    }

    private void ensureStarterPet(long userId) {
        Boolean exists = jdbc.queryForObject("select count(*) > 0 from partner_pet where user_id = ?", Boolean.class, userId);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        jdbc.update(
            """
                insert into partner_pet (public_id, user_id, species_code, name, breed, fur_color, selected)
                values (?, ?, 'CAT', '小橘', '中华田园猫', '橘白', 1)
                """,
            ids.next(), userId
        );
    }

    private void ensureWallet(long userId) {
        jdbc.update("insert ignore into user_wallet (user_id) values (?)", userId);
    }

    private WalletView wallet(long userId) {
        return jdbc.queryForObject(
            "select coin_balance, lifetime_coins from user_wallet where user_id = ?",
            (rs, row) -> new WalletView(rs.getInt("coin_balance"), rs.getInt("lifetime_coins")),
            userId
        );
    }

    private WalletRow walletForUpdate(long userId) {
        return jdbc.queryForObject(
            "select coin_balance, lifetime_coins from user_wallet where user_id = ? for update",
            (rs, row) -> new WalletRow(rs.getInt("coin_balance"), rs.getInt("lifetime_coins")),
            userId
        );
    }

    private List<PetView> pets(long userId) {
        return jdbc.query(
            """
                select public_id, species_code, name, breed, fur_color, level, affection, selected, last_interacted_at
                from partner_pet where user_id = ? order by selected desc, created_at
                """,
            (rs, row) -> new PetView(
                rs.getString("public_id"), rs.getString("species_code"), speciesName(rs.getString("species_code")),
                rs.getString("name"), rs.getString("breed"), rs.getString("fur_color"),
                rs.getInt("level"), rs.getInt("affection"), requirement(rs.getInt("level")),
                rs.getBoolean("selected"), rs.getTimestamp("last_interacted_at") == null ? null : rs.getTimestamp("last_interacted_at").toInstant()
            ),
            userId
        );
    }

    private PetView selectedPet(long userId) {
        return pets(userId).stream().filter(PetView::selected).findFirst().orElseGet(() -> pets(userId).getFirst());
    }

    private PetView pet(long userId, String publicId) {
        return pets(userId).stream()
            .filter(item -> item.publicId().equals(publicId))
            .findFirst()
            .orElseThrow(() -> notFound("PET_NOT_FOUND"));
    }

    private PetRow petRow(long userId, String publicId) {
        PetRow row = jdbc.query(
            """
                select id, public_id, species_code, name, breed, fur_color, level, affection
                from partner_pet where user_id = ? and public_id = ?
                """,
            rs -> rs.next() ? new PetRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("species_code"),
                rs.getString("name"), rs.getString("breed"), rs.getString("fur_color"),
                rs.getInt("level"), rs.getInt("affection")
            ) : null,
            userId, publicId
        );
        if (row == null) throw notFound("PET_NOT_FOUND");
        return row;
    }

    private List<ShopItemView> shopItems() {
        return jdbc.query(
            """
                select id, public_id, item_type, species_code, name, description, price, affection_gain, template_source
                from partner_shop_item where active = 1 order by item_type, price
                """,
            (rs, row) -> itemView(new ShopItemRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("item_type"),
                rs.getString("species_code"), rs.getString("name"), rs.getString("description"),
                rs.getInt("price"), rs.getInt("affection_gain"), rs.getString("template_source")
            ))
        );
    }

    private ShopItemRow shopItem(String publicId) {
        ShopItemRow row = jdbc.query(
            """
                select id, public_id, item_type, species_code, name, description, price, affection_gain, template_source
                from partner_shop_item where public_id = ? and active = 1
                """,
            rs -> rs.next() ? new ShopItemRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("item_type"),
                rs.getString("species_code"), rs.getString("name"), rs.getString("description"),
                rs.getInt("price"), rs.getInt("affection_gain"), rs.getString("template_source")
            ) : null,
            publicId
        );
        if (row == null) throw notFound("SHOP_ITEM_NOT_FOUND");
        return row;
    }

    private ShopItemView itemView(ShopItemRow row) {
        return new ShopItemView(
            row.publicId(), row.itemType(), row.speciesCode(), row.speciesCode() == null ? "通用" : speciesName(row.speciesCode()),
            row.name(), row.description(), row.price(), row.affectionGain(), row.templateSource()
        );
    }

    private List<TemplateLibraryView> templateLibraries() {
        return List.of(
            new TemplateLibraryView("Kenney Animal Pack", "卡通 2D 动物基础姿态、UI 图标和装饰道具，适合猫、狗、兔子等轻量动画。", "https://kenney.nl/assets?q=animal"),
            new TemplateLibraryView("OpenGameArt 2D Animals", "社区授权素材较丰富，适合查找食物、饰品和像素/卡通动物变体。", "https://opengameart.org/"),
            new TemplateLibraryView("CraftPix Pet Game Assets", "成套宠物、房间、食物和装饰素材，适合做更完整的伙伴房间。", "https://craftpix.net/categorys/game-assets/"),
            new TemplateLibraryView("Game-icons.net", "大量 SVG 道具图标，可用作金币、食物、饰品和状态反馈图标。", "https://game-icons.net/")
        );
    }

    private Affection addAffection(int level, int affection, int delta) {
        int nextLevel = level;
        int nextAffection = Math.max(0, affection + delta);
        while (nextLevel < 20 && nextAffection >= requirement(nextLevel)) {
            nextAffection -= requirement(nextLevel);
            nextLevel++;
        }
        if (nextLevel == 20) {
            nextAffection = Math.min(999, nextAffection);
        }
        return new Affection(nextLevel, nextAffection);
    }

    private int requirement(int level) {
        return level >= 20 ? 999 : level * 10;
    }

    private LocalDate localDate(long userId, Instant now) {
        String timezone = jdbc.queryForObject("select timezone from sys_user where id = ?", String.class, userId);
        return now.atZone(ZoneId.of(timezone)).toLocalDate();
    }

    private String species(String value) {
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PET_SPECIES_REQUIRED", "请选择伙伴类型");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!SPECIES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PET_SPECIES", "伙伴类型无效");
        }
        return normalized;
    }

    private String required(String value, String code, String message, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
        return value.trim();
    }

    private String speciesName(String code) {
        return switch (code) {
            case "CAT" -> "猫";
            case "DOG" -> "狗";
            case "HAMSTER" -> "仓鼠";
            case "SNAKE" -> "蛇";
            case "RABBIT" -> "兔子";
            case "BIRD" -> "小鸟";
            case "TURTLE" -> "乌龟";
            case "FOX" -> "狐狸";
            default -> code;
        };
    }

    private ApiException notFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Resource not found");
    }

    public record PartnerProfile(WalletView wallet, List<PetView> pets, PetView selectedPet, List<ShopItemView> shopItems, List<TemplateLibraryView> templateLibraries) {
    }

    public record WalletView(int coinBalance, int lifetimeCoins) {
    }

    public record PetView(String publicId, String speciesCode, String speciesName, String name, String breed, String furColor, int level, int affection, int nextLevelAffection, boolean selected, Instant lastInteractedAt) {
    }

    public record InteractionResult(PetView pet, int affectionDelta, boolean rewarded, LocalDate interactionDate) {
    }

    public record ShopItemView(String publicId, String itemType, String speciesCode, String speciesName, String name, String description, int price, int affectionGain, String templateSource) {
    }

    public record TemplateLibraryView(String name, String description, String url) {
    }

    public record CreatePetCommand(String speciesCode, String name, String breed, String furColor) {
    }

    public record UpdatePetCommand(String name, String breed, String furColor) {
    }

    public record PurchaseCommand(String petPublicId, String itemPublicId) {
    }

    public record PurchaseResult(String purchasePublicId, WalletView wallet, PetView pet, ShopItemView item, Instant purchasedAt) {
    }

    private record WalletRow(int coinBalance, int lifetimeCoins) {
    }

    private record PetRow(long id, String publicId, String speciesCode, String name, String breed, String furColor, int level, int affection) {
    }

    private record ShopItemRow(long id, String publicId, String itemType, String speciesCode, String name, String description, int price, int affectionGain, String templateSource) {
    }

    private record Affection(int level, int affection) {
    }
}
