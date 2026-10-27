package dev.wes.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 聊天输入框正上方的「方块选择」总开关。
 *
 * <h2>为什么要这个开关</h2>
 * <p>默认情况下，只要输入框里打出 {@code //set } 这种「指令 + 空格」，
 * 面板就会自己弹出来。对大多数人是好事，但如果玩家想手动敲指令、
 * 或者只是恰好打到这几个字符，弹窗就很烦。所以给一个可见的开关：
 * 开着才触发，关掉之后聊天框就是原版聊天框。</p>
 *
 * <h2>为什么自己画，不做成 {@code Button}</h2>
 * <p>面板里的控件都不注册进 {@code Screen}（见 {@link dev.wes.gui.ChatBlockPanel}），
 * 这个按钮更极端 —— 面板关着的时候它也得显示，所以不能挂在面板的控件列表里。
 * 直接画一个 14px 高的小条最省事：两行 {@code fill} + 一行文字，
 * 不必卷入 {@code Screen} 的子控件生命周期，也不会和原版输入框抢焦点。</p>
 *
 * <h2>状态存在哪</h2>
 * <p>一个静态字段，作用域是「本次游戏进程」：重开游戏会回到默认的开。
 * 想让它跨启动记住，把它落到 config 目录即可，但那样就多了一处可能抛
 * {@code IOException} 的地方，目前不值得。</p>
 */
public final class ChatToggle {

    /* ------------------------------ 尺寸 ------------------------------ */

    private static final int HEIGHT = 14;
    private static final int PAD_X = 5;
    private static final int DOT = 5;
    private static final int GAP = 4;
    /** 按钮和输入框之间留的空隙。 */
    private static final int GAP_ABOVE_INPUT = 2;

    /* ------------------------------ 配色 ------------------------------ */

    private static final int COL_BG_ON = 0xE01B2A1C;
    private static final int COL_BG_ON_HOVER = 0xF0263A28;
    private static final int COL_BG_OFF = 0xE01A1A1A;
    private static final int COL_BG_OFF_HOVER = 0xF02A2A2A;
    private static final int COL_BORDER_ON = 0xFF5FBF74;
    private static final int COL_BORDER_OFF = 0xFF6E6E6E;
    private static final int COL_TEXT_ON = 0xFFE6F2E8;
    private static final int COL_TEXT_OFF = 0xFF9A9A9A;
    private static final int COL_DOT_ON = 0xFF5FBF74;
    private static final int COL_DOT_OFF = 0xFF616161;

    /** 默认开着：装好模组后的行为和没有这个开关时完全一致。 */
    private static boolean enabled = true;

    private ChatToggle() {
    }

    /** @return 是否允许「打字触发面板」 */
    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /* ============================== 几何 ============================== */

    /**
     * 按钮贴在哪里：输入框左上角的正上方。
     *
     * <p>位置每次都从输入框现读，而不是自己算 {@code height - 12} ——
     * 窗口尺寸变化时 {@code ChatScreen} 会重建输入框，跟着它走永远不会错位。</p>
     */
    private static int buttonX(EditBox chatField) {
        return chatField.getX();
    }

    private static int buttonY(EditBox chatField) {
        return Math.max(0, chatField.getY() - HEIGHT - GAP_ABOVE_INPUT);
    }

    private static int buttonWidth(Font font, String text) {
        return PAD_X * 2 + DOT + GAP + font.width(text);
    }

    private static String labelText() {
        return Component.translatable(enabled ? "wes.toggle.on" : "wes.toggle.off").getString();
    }

    private static boolean isHovered(double mouseX, double mouseY, int x, int y, int w) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + HEIGHT;
    }

    /* ============================== 绘制 ============================== */

    /**
     * 画出开关。调用方要保证这是在 {@code ChatScreen#render} 的最后一步，
     * 否则会被聊天记录或输入框盖住。
     */
    public static void render(GuiGraphics graphics, EditBox chatField, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        String text = labelText();
        int w = buttonWidth(font, text);
        int x = buttonX(chatField);
        int y = buttonY(chatField);
        boolean hovered = isHovered(mouseX, mouseY, x, y, w);

        int bg;
        if (enabled) {
            bg = hovered ? COL_BG_ON_HOVER : COL_BG_ON;
        } else {
            bg = hovered ? COL_BG_OFF_HOVER : COL_BG_OFF;
        }
        int border = enabled ? COL_BORDER_ON : COL_BORDER_OFF;
        int fg = enabled ? COL_TEXT_ON : COL_TEXT_OFF;

        graphics.fill(x, y, x + w, y + HEIGHT, bg);
        drawBorder(graphics, x, y, w, HEIGHT, border);

        // 左边一个小圆点：开是绿的、关是灰的，比读文字更快
        int dotY = y + (HEIGHT - DOT) / 2;
        graphics.fill(x + PAD_X, dotY, x + PAD_X + DOT, dotY + DOT,
                enabled ? COL_DOT_ON : COL_DOT_OFF);

        graphics.drawString(font, text, x + PAD_X + DOT + GAP, y + (HEIGHT - 8) / 2, fg, true);
    }

    /** 1px 描边。{@code fill} 的右下边界是开区间，所以第二行就是底边。 */
    private static void drawBorder(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y + 1, x + 1, y + h - 1, color);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /* ============================== 点击 ============================== */

    /**
     * 处理点击。
     *
     * @return true 表示点在开关上、状态已经翻转，调用方要把这次点击吃掉
     */
    public static boolean mouseClicked(EditBox chatField, double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        Font font = Minecraft.getInstance().font;
        int w = buttonWidth(font, labelText());
        int x = buttonX(chatField);
        int y = buttonY(chatField);
        if (!isHovered(mouseX, mouseY, x, y, w)) {
            return false;
        }
        enabled = !enabled;
        return true;
    }
}
