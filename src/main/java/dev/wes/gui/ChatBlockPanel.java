package dev.wes.gui;

import dev.wes.worldedit.BlockCatalog;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 覆盖在聊天输入框上方的方块选择浮层。
 *
 * <h2>为什么不是 Screen</h2>
 * <p>需求是「在聊天框里选，而不是另开一个界面」，所以这里不是 {@code Screen}，
 * 而是挂在 {@link ChatScreen} 实例上的一个普通对象：</p>
 * <ul>
 *   <li>绘制：由 ChatScreenMixin 在 {@code ChatScreen.render} 的 TAIL 里调用
 *       {@link #render} —— 此时聊天输入框已经画完，我们在它上方铺面板；</li>
 *   <li>输入：由 ChatScreenMixin 在 mouseClicked / mouseScrolled / keyPressed 的 HEAD 里转发；</li>
 *   <li>焦点：控件不注册进 Screen，靠 {@link ParentElement#setFocused} 手动设焦点。
 *       这样 {@code Screen} 从 {@code ParentElement} 继承来的
 *       {@code charTyped -> getFocused().charTyped()} 就会把字符送进我们正在编辑的输入框，
 *       不需要自己重写字符输入（1.20.1 的 ChatScreen 也没有 charTyped 可以混入）。</li>
 * </ul>
 *
 * <p>确认时不会发送任何东西，只把 {@code //set 50%minecraft:stone,...} 写回聊天输入框，
 * 玩家自己看一眼再按回车，仍然可以继续手改。</p>
 */
public final class ChatBlockPanel {

    /* ------------------------------ 常量 ------------------------------ */

    private static final int ROW_H = 20;
    private static final int SEL_ROW_H = 24;
    private static final int FIELD_W = 38;
    private static final int FIELD_H = 16;
    private static final int STATE_BTN_W = 16;
    private static final int PROP_ROW_H = 18;
    private static final double SCROLL_STEP = 16.0D;

    private static final int COL_PANEL_BG = 0xF0101010;
    private static final int COL_PANEL_BORDER = 0xFF3A3A3A;
    private static final int COL_ROW_HOVER = 0x33FFFFFF;
    private static final int COL_ROW_SELECTED = 0x2A2ECC71;
    private static final int COL_TEXT = 0xFFDDDDDD;
    private static final int COL_TEXT_SELECTED = 0xFF7BE08A;
    private static final int COL_TEXT_DIM = 0xFF888888;
    private static final int COL_ERROR = 0xFFFF6B6B;

    /* ------------------------------ 宿主 ------------------------------ */

    private final ChatScreen screen;
    private final ParentElement parent;
    private final TextRenderer textRenderer;
    /** 聊天输入框：既当「搜索框之外的那个输入行」，也是确认后回填指令的地方。 */
    private TextFieldWidget chatField;

    /* ------------------------------ 数据 ------------------------------ */

    private final List<Block> allBlocks = new ArrayList<>();
    private final List<Block> filtered = new ArrayList<>();
    /** 已选方块 → 百分比（0 = 留空，确认时均分）。 */
    private final Map<Block, Integer> selectedBlocks = new LinkedHashMap<>();
    /** 已选方块 → 选中的方块状态（默认就是默认状态）。 */
    private final Map<Block, BlockState> selectedStates = new LinkedHashMap<>();
    /** 与 {@link #percentOrder} 一一对应的百分比输入框。 */
    private final List<TextFieldWidget> percentWidgets = new ArrayList<>();
    private final List<Block> percentOrder = new ArrayList<>();
    /** 正在编辑方块状态的那个方块；null 表示当前是列表视图。 */
    private Block stateEditing;
    private double propScroll;

    private TextFieldWidget searchField;
    private ButtonWidget confirmButton;
    private ButtonWidget cancelButton;

    private boolean open;
    private String query = "";
    private String statusMessage = "";
    private double listScroll;
    private double selScroll;

    /* ------------------------------ 几何 ------------------------------ */

    private int px, py, pw, ph;
    private int listX, listY, listW, listH;
    private int selX, selY, selW, selH;

    public ChatBlockPanel(ChatScreen screen, TextRenderer textRenderer, TextFieldWidget chatField) {
        this.screen = screen;
        this.parent = screen;
        this.textRenderer = textRenderer;
        this.chatField = chatField;
    }

    public boolean isOpen() {
        return this.open;
    }

    /* ============================== 开关 ============================== */

    public void open() {
        if (this.open) {
            return;
        }
        if (this.allBlocks.isEmpty()) {
            this.allBlocks.addAll(BlockCatalog.collectAll());
        }
        this.open = true;
        this.statusMessage = "";
        this.query = "";
        this.listScroll = 0.0D;
        this.selScroll = 0.0D;
        this.stateEditing = null;
        this.propScroll = 0.0D;
        // 把残留的 "//set " 清掉：面板开着的时候这半截指令没有任何意义，
        // 万一玩家直接按回车还会发给服务器。
        this.chatField.setText("");
        this.computeLayout();
        this.buildWidgets();
        this.setFocus(this.searchField);
    }

    public void close() {
        if (!this.open) {
            return;
        }
        this.open = false;
        this.stateEditing = null;
        this.searchField = null;
        this.confirmButton = null;
        this.cancelButton = null;
        this.percentWidgets.clear();
        this.percentOrder.clear();
        this.setFocus(this.chatField); // 焦点还给聊天输入框，玩家可以接着打字
    }

    /** 窗口尺寸变化后（ChatScreen 会重建 chatField）重新摆位。 */
    public void onResize(TextFieldWidget newChatField) {
        this.chatField = newChatField;
        if (!this.open) {
            return;
        }
        TextFieldWidget focused = this.parent.getFocused() instanceof TextFieldWidget f ? f : null;
        this.computeLayout();
        this.buildWidgets();
        if (focused != null) {
            this.setFocus(this.searchField);
        }
    }

    /* ============================== 布局 ============================== */

    private void computeLayout() {
        // 面板不铺满全屏：只占上方约 2/3，底部留给聊天记录和输入行，
        // 这样开着面板也能看到别人说的话。
        this.px = 4;
        this.pw = this.screen.width - 8;
        this.py = 4;
        this.ph = MathHelper.clamp(Math.round(this.screen.height * 0.68F), 150, 260);

        int top = this.py + 24;              // 标题占掉上面 24px，搜索框从这里开始
        int bottom = this.py + this.ph - 30; // 底部留给按钮
        int innerW = this.pw - 18;

        this.listX = this.px + 6;
        this.listW = Math.max(120, innerW * 58 / 100);
        this.listY = top + 20;               // 搜索框高 16 + 4 间距
        this.listH = Math.max(40, bottom - this.listY);

        this.selX = this.listX + this.listW + 6;
        this.selW = Math.max(100, this.px + this.pw - 6 - this.selX);
        this.selY = this.listY;
        this.selH = this.listH;
    }

    private void buildWidgets() {
        this.setFocus(null);
        this.percentWidgets.clear();
        this.percentOrder.clear();

        this.searchField = new TextFieldWidget(this.textRenderer,
                this.listX, this.listY - 20, this.listW, 16, Text.translatable("wes.search"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(Text.translatable("wes.search").formatted(Formatting.DARK_GRAY));
        this.searchField.setText(this.query);
        this.searchField.setChangedListener(text -> {
            this.query = text;
            this.applyFilter(text);
            this.listScroll = 0.0D;
        });

        for (Block block : this.selectedBlocks.keySet()) {
            TextFieldWidget field = new TextFieldWidget(this.textRenderer, 0, 0, FIELD_W, FIELD_H, Text.literal("%"));
            field.setMaxLength(3);
            field.setTextPredicate(text -> text.isEmpty() || text.matches("[0-9]{1,3}"));
            int value = this.selectedBlocks.getOrDefault(block, 0);
            field.setText(value <= 0 ? "" : Integer.toString(value));
            field.setChangedListener(text -> {
                int parsed = 0;
                if (!text.isEmpty()) {
                    try {
                        parsed = Integer.parseInt(text);
                    } catch (NumberFormatException ignored) {
                        // setTextPredicate 已经挡掉了非数字
                    }
                }
                this.selectedBlocks.put(block, MathHelper.clamp(parsed, 0, 100));
                this.statusMessage = "";
            });
            this.percentWidgets.add(field);
            this.percentOrder.add(block);
        }

        int buttonY = this.py + this.ph - 26;
        this.confirmButton = ButtonWidget.builder(Text.translatable("wes.confirm"), b -> this.confirm())
                .dimensions(this.px + this.pw - 6 - 200, buttonY, 96, 18).build();
        this.cancelButton = ButtonWidget.builder(Text.translatable("wes.cancel"), b -> this.close())
                .dimensions(this.px + this.pw - 6 - 98, buttonY, 92, 18).build();

        this.applyFilter(this.query);
        this.layoutWidgets();
    }

    private void applyFilter(String text) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        this.filtered.clear();
        for (Block block : this.allBlocks) {
            if (q.isEmpty()
                    || block.getName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || BlockCatalog.idOf(block).contains(q)) {
                this.filtered.add(block);
            }
        }
        this.clampScroll();
    }

    private void clampScroll() {
        double maxList = Math.max(0.0D, this.filtered.size() * (double) ROW_H - this.listH);
        this.listScroll = MathHelper.clamp(this.listScroll, 0.0D, maxList);
        double maxSel = Math.max(0.0D, this.selectedBlocks.size() * (double) SEL_ROW_H + 8.0D - this.selH);
        this.selScroll = MathHelper.clamp(this.selScroll, 0.0D, maxSel);
    }

    private void layoutWidgets() {
        this.clampScroll();

        if (this.searchField != null) {
            this.searchField.setX(this.listX);
            this.searchField.setY(this.listY - 20);
            this.searchField.setWidth(this.listW);
        }

        for (int i = 0; i < this.percentWidgets.size(); i++) {
            TextFieldWidget field = this.percentWidgets.get(i);
            int rowTop = this.selY + 4 + i * SEL_ROW_H - (int) this.selScroll;
            field.setX(this.selX + this.selW - FIELD_W - 8);
            field.setY(rowTop + (SEL_ROW_H - FIELD_H) / 2 - 1);
            field.setWidth(FIELD_W);
            // 滚出视口的控件要隐藏，否则会画到面板外面
            field.visible = rowTop + SEL_ROW_H > this.selY && rowTop < this.selY + this.selH;
        }
    }

    /* ============================== 渲染 ============================== */

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!this.open) {
            return;
        }
        if (this.stateEditing != null) {
            this.drawStateEditor(context, mouseX, mouseY);
            return;
        }

        this.drawPanels(context, mouseX, mouseY);

        // 控件自己不会渲染（没注册进 Screen），这里按顺序手动画
        if (this.searchField != null) {
            this.searchField.render(context, mouseX, mouseY, delta);
            if (this.searchField.getText().isEmpty()) {
                context.drawText(this.textRenderer,
                        Text.translatable("wes.search").getString(),
                        this.listX + 4, this.listY - 16, 0x66FFFFFF, false);
            }
        }
        for (TextFieldWidget field : this.percentWidgets) {
            if (field.visible) {
                field.render(context, mouseX, mouseY, delta);
            }
        }
        if (this.confirmButton != null) {
            this.confirmButton.render(context, mouseX, mouseY, delta);
        }
        if (this.cancelButton != null) {
            this.cancelButton.render(context, mouseX, mouseY, delta);
        }
    }

    private void drawPanels(DrawContext context, int mouseX, int mouseY) {
        context.fill(this.px, this.py, this.px + this.pw, this.py + this.ph, COL_PANEL_BG);
        context.drawBorder(this.px, this.py, this.pw, this.ph, COL_PANEL_BORDER);

        context.drawText(this.textRenderer, this.title(), this.px + 6, this.py + 8, 0xFFFFFFFF, false);
        context.drawText(this.textRenderer,
                Text.translatable("wes.selected", this.selectedBlocks.size()),
                this.selX, this.listY - 16, COL_TEXT_DIM, false);

        this.drawBlockList(context, mouseX, mouseY);
        this.drawSelectedList(context, mouseX, mouseY);
        this.drawScrollbars(context);

        if (!this.statusMessage.isEmpty()) {
            context.drawText(this.textRenderer, Text.literal(this.statusMessage),
                    this.px + 6, this.py + this.ph - 20, COL_ERROR, false);
        } else {
            context.drawText(this.textRenderer, Text.translatable("wes.hint"),
                    this.px + 6, this.py + this.ph - 20, COL_TEXT_DIM, false);
        }
    }

    private Text title() {
        return Text.translatable("wes.title");
    }

    private void drawBlockList(DrawContext context, int mouseX, int mouseY) {
        context.enableScissor(this.listX, this.listY, this.listX + this.listW, this.listY + this.listH);

        int first = Math.max(0, (int) (this.listScroll / ROW_H));
        int rows = this.listH / ROW_H + 2;
        int last = Math.min(this.filtered.size(), first + rows);

        for (int i = first; i < last; i++) {
            Block block = this.filtered.get(i);
            int y = this.listY + i * ROW_H - (int) this.listScroll;
            boolean hovered = isInside(mouseX, mouseY, this.listX, y, this.listW, ROW_H);
            boolean chosen = this.selectedBlocks.containsKey(block);

            if (hovered) {
                context.fill(this.listX, y, this.listX + this.listW, y + ROW_H - 1, COL_ROW_HOVER);
            } else if (chosen) {
                context.fill(this.listX, y, this.listX + this.listW, y + ROW_H - 1, COL_ROW_SELECTED);
            }

            context.drawItem(new ItemStack(block), this.listX + 2, y + 2);
            String name = this.textRenderer.trimToWidth(block.getName().getString(), this.listW - 26);
            context.drawText(this.textRenderer, name, this.listX + 22, y + 6,
                    chosen ? COL_TEXT_SELECTED : COL_TEXT, false);
        }

        context.disableScissor();

        if (this.filtered.isEmpty()) {
            context.drawText(this.textRenderer, Text.translatable("wes.empty"),
                    this.listX + 4, this.listY + 4, COL_TEXT_DIM, false);
        }
    }

    private void drawSelectedList(DrawContext context, int mouseX, int mouseY) {
        context.enableScissor(this.selX, this.selY, this.selX + this.selW, this.selY + this.selH);

        int index = 0;
        for (Block block : this.selectedBlocks.keySet()) {
            int y = this.selY + 4 + index * SEL_ROW_H - (int) this.selScroll;
            index++;
            if (y + SEL_ROW_H < this.selY || y > this.selY + this.selH) {
                continue;
            }
            boolean hovered = isInside(mouseX, mouseY, this.selX, y, this.selW, SEL_ROW_H - 2);
            context.fill(this.selX, y, this.selX + this.selW, y + SEL_ROW_H - 2,
                    hovered ? COL_ROW_HOVER : 0x18FFFFFF);
            context.drawItem(new ItemStack(block), this.selX + 4, y + 4);

            int nameWidth = this.selW - 24 - FIELD_W - 8 - STATE_BTN_W - 8;
            String name = this.textRenderer.trimToWidth(block.getName().getString(), Math.max(10, nameWidth));
            context.drawText(this.textRenderer, name, this.selX + 24, y + 8, 0xFFEEEEEE, false);

            if (hasProperties(block)) {
                int bx = this.stateButtonX();
                int by = y + 4;
                boolean hover = isInside(mouseX, mouseY, bx, by, STATE_BTN_W, 16);
                context.fill(bx, by, bx + STATE_BTN_W, by + 16, hover ? 0xFF3F6B4A : 0xFF2A2A2A);
                context.drawBorder(bx, by, STATE_BTN_W, 16, 0xFF555555);
                context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("wes.state"),
                        bx + STATE_BTN_W / 2, by + 4, 0xFFCCCCCC);
            }
        }

        context.disableScissor();

        if (this.selectedBlocks.isEmpty()) {
            context.drawText(this.textRenderer, Text.translatable("wes.selected.empty"),
                    this.selX + 4, this.selY + 4, COL_TEXT_DIM, false);
        }
    }

    private void drawScrollbars(DrawContext context) {
        drawScrollbar(context, this.listX, this.listY, this.listW, this.listH,
                this.listScroll, this.filtered.size() * ROW_H);
        drawScrollbar(context, this.selX, this.selY, this.selW, this.selH,
                this.selScroll, this.selectedBlocks.size() * SEL_ROW_H + 8);
    }

    private void drawScrollbar(DrawContext context, int x, int y, int w, int h,
                               double scroll, int contentHeight) {
        if (contentHeight <= h) {
            return;
        }
        int barH = Math.max(12, (int) ((double) h * h / contentHeight));
        int barY = y + (int) ((h - barH) * (scroll / (contentHeight - h)));
        context.fill(x + w - 4, y, x + w, y + h, 0x40000000);
        context.fill(x + w - 4, barY, x + w, barY + barH, 0xFF9A9A9A);
    }

    /* ============================== 输入 ============================== */

    /** @return true 表示这次点击被面板吃掉了 */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.open || button != 0) {
            return false;
        }
        if (!this.contains(mouseX, mouseY)) {
            return false;
        }
        if (this.stateEditing != null) {
            return this.clickStateEditor(mouseX, mouseY);
        }

        // 1) 控件优先：输入框先设焦点再转发点击（这样光标位置才算得准）
        if (this.searchField != null && this.searchField.mouseClicked(mouseX, mouseY, button)) {
            this.setFocus(this.searchField);
            return true;
        }
        for (TextFieldWidget field : this.percentWidgets) {
            if (field.visible && field.mouseClicked(mouseX, mouseY, button)) {
                this.setFocus(field);
                return true;
            }
        }
        if (this.confirmButton != null && this.confirmButton.mouseClicked(mouseX, mouseY, button)) {
            return true; // ButtonWidget 内部已经触发 onPress
        }
        if (this.cancelButton != null && this.cancelButton.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // 2) 左侧列表：加入 / 移出
        if (isInside(mouseX, mouseY, this.listX, this.listY, this.listW, this.listH)) {
            int index = (int) ((mouseY - this.listY + this.listScroll) / ROW_H);
            if (index >= 0 && index < this.filtered.size()) {
                this.toggle(this.filtered.get(index));
            }
            return true;
        }

        // 3) 右侧列表：点「态」按钮进状态编辑，点别处移除（点输入框的情况上面已经拦掉了）
        if (isInside(mouseX, mouseY, this.selX, this.selY, this.selW, this.selH)) {
            int index = (int) ((mouseY - this.selY - 4 + this.selScroll) / SEL_ROW_H);
            List<Block> order = new ArrayList<>(this.selectedBlocks.keySet());
            if (index >= 0 && index < order.size()) {
                Block block = order.get(index);
                int rowTop = this.selY + 4 + index * SEL_ROW_H - (int) this.selScroll;
                if (hasProperties(block)
                        && isInside(mouseX, mouseY, this.stateButtonX(), rowTop + 4, STATE_BTN_W, 16)) {
                    this.stateEditing = block;
                    this.propScroll = 0.0D;
                    return true;
                }
                this.selectedBlocks.remove(block);
                this.selectedStates.remove(block);
                this.statusMessage = "";
                this.rebuildSelection();
            }
            return true;
        }

        return true; // 面板空白处，吃掉
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!this.open) {
            return false;
        }
        if (this.stateEditing != null) {
            this.propScroll -= amount * SCROLL_STEP;
            this.clampPropScroll();
            return true;
        }
        if (isInside(mouseX, mouseY, this.listX, this.listY, this.listW, this.listH)) {
            this.listScroll -= amount * SCROLL_STEP;
            this.clampScroll();
            return true;
        }
        if (isInside(mouseX, mouseY, this.selX, this.selY, this.selW, this.selH)) {
            this.selScroll -= amount * SCROLL_STEP;
            this.clampScroll();
            this.layoutWidgets();
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.open) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.stateEditing != null) {
                this.stateEditing = null; // 状态编辑中先退回列表
            } else {
                this.close();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.stateEditing != null) {
                this.stateEditing = null;
                return true;
            }
            this.confirm();
            return true;
        }
        // 其余按键转发给当前有焦点的控件（退格、方向键、Ctrl+V 都靠它）
        Element focused = this.parent.getFocused();
        if (focused != null && focused != this.chatField) {
            return focused.keyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    /* ============================== 内部逻辑 ============================== */

    public boolean contains(double mouseX, double mouseY) {
        return this.open && isInside(mouseX, mouseY, this.px, this.py, this.pw, this.ph);
    }

    private void setFocus(Element element) {
        this.parent.setFocused(element);
    }

    private void toggle(Block block) {
        if (this.selectedBlocks.containsKey(block)) {
            this.selectedBlocks.remove(block);
            this.selectedStates.remove(block);
            if (this.stateEditing == block) {
                this.stateEditing = null;
            }
        } else {
            this.selectedBlocks.put(block, 0);
            this.selectedStates.put(block, block.getDefaultState());
        }
        this.statusMessage = "";
        this.rebuildSelection();
    }

    /**
     * 选中集合变了以后重建右侧控件。
     *
     * <p>控件没有注册进 Screen，所以「移除」只是丢掉引用；但必须先把 Screen 的焦点清掉，
     * 否则它还会指着一个已经作废的输入框，字符就送不进去了。</p>
     */
    private void rebuildSelection() {
        Block focusedBlock = null;
        int cursor = 0;
        for (int i = 0; i < this.percentWidgets.size(); i++) {
            if (this.percentWidgets.get(i).isFocused()) {
                focusedBlock = this.percentOrder.get(i);
                cursor = this.percentWidgets.get(i).getCursor();
                break;
            }
        }

        this.buildWidgets();

        if (focusedBlock != null) {
            int index = this.percentOrder.indexOf(focusedBlock);
            if (index >= 0) {
                TextFieldWidget field = this.percentWidgets.get(index);
                this.setFocus(field);
                field.setCursor(MathHelper.clamp(cursor, 0, field.getText().length()));
                return;
            }
        }
        this.setFocus(this.searchField);
    }

    /**
     * 确认：补齐百分比 → 拼指令 → 写回聊天输入框。
     *
     * <p>规则：填了数字的照用；留空的均分剩余（余数从前往后 +1）；
     * 总和超过 100 直接报错。</p>
     */
    private void confirm() {
        if (this.selectedBlocks.isEmpty()) {
            this.statusMessage = Text.translatable("wes.err.empty").getString();
            return;
        }

        List<Block> order = new ArrayList<>(this.selectedBlocks.keySet());
        int[] percents = new int[order.size()];
        List<Integer> blanks = new ArrayList<>();
        int used = 0;

        for (int i = 0; i < order.size(); i++) {
            int value = this.readPercent(i);
            percents[i] = value;
            if (value > 0) {
                used += value;
            } else {
                blanks.add(i);
            }
        }

        if (used > 100) {
            this.statusMessage = Text.translatable("wes.err.over", used).getString();
            return;
        }

        if (!blanks.isEmpty()) {
            int remaining = 100 - used;
            int base = remaining / blanks.size();
            int extra = remaining % blanks.size();
            for (int i = 0; i < blanks.size(); i++) {
                percents[blanks.get(i)] = base + (i < extra ? 1 : 0);
            }
        }

        List<String> ids = new ArrayList<>();
        List<Integer> percentsOut = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            if (percents[i] <= 0) {
                continue;
            }
            Block block = order.get(i);
            ids.add(Registries.BLOCK.getId(block) + stateSuffix(this.selectedStates.get(block)));
            percentsOut.add(percents[i]);
        }

        if (ids.isEmpty()) {
            this.statusMessage = Text.translatable("wes.err.empty").getString();
            return;
        }

        String body;
        if (ids.size() == 1) {
            // 关键：WorldEdit 的 RandomPatternParser 里有一句
            //     if (patterns.size() == 1) return null;
            // 也就是说「只有一个条目」时百分比语法根本不生效，
            // "100%minecraft:blue_bed" 会被当成一个方块名去查，然后报
            // "无法识别方块名称"。所以单方块必须不带百分比。
            body = ids.get(0);
        } else {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(percentsOut.get(i)).append('%').append(ids.get(i));
            }
            body = builder.toString();
        }

        String result = "//set " + body;
        if (result.length() > 240) {
            this.statusMessage = Text.translatable("wes.err.long", result.length()).getString();
            return;
        }

        // 只回填，不发送 —— 玩家自己按回车，还能继续改
        this.chatField.setText(result);
        this.chatField.setCursorToEnd();
        this.close();
    }

    private int readPercent(int index) {
        TextFieldWidget field = this.percentWidgets.get(index);
        if (field == null || field.getText().isBlank()) {
            return 0;
        }
        try {
            return MathHelper.clamp(Integer.parseInt(field.getText().trim()), 0, 100);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /* ============================== 方块状态 ============================== */

    /** 右侧「态」按钮的 x 坐标（在百分比输入框左边）。 */
    private int stateButtonX() {
        return this.selX + this.selW - FIELD_W - 8 - STATE_BTN_W - 4;
    }

    private static boolean hasProperties(Block block) {
        return !block.getStateManager().getProperties().isEmpty();
    }

    /**
     * 把方块状态拼成 WorldEdit 认的后缀，只写「和默认值不一样」的属性。
     *
     * <p>例如床选了 head 部分就是 {@code [part=head]}；全部默认则返回空串。
     * 只写差异部分能让指令短很多，WorldEdit 对没写的属性会用默认值。</p>
     */
    private static String stateSuffix(BlockState state) {
        if (state == null) {
            return "";
        }
        BlockState defaults = state.getBlock().getDefaultState();
        StringBuilder builder = new StringBuilder();
        for (Property<?> property : state.getProperties()) {
            Object value = state.get(property);
            Object defaultValue = defaults.get(property);
            if (!value.equals(defaultValue)) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(property.getName()).append('=').append(valueName(property, state));
            }
        }
        return builder.length() == 0 ? "" : "[" + builder + "]";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueName(Property property, BlockState state) {
        return property.name(state.get(property));
    }

    /** 属性值循环切换：dir = -1 上一个，+1 下一个。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState cycleProperty(BlockState state, Property property, int dir) {
        List<Comparable> values = new ArrayList<>(property.getValues());
        if (values.isEmpty()) {
            return state;
        }
        int index = values.indexOf(state.get(property));
        if (index < 0) {
            index = 0;
        }
        int next = (index + dir + values.size()) % values.size();
        return state.with(property, values.get(next));
    }

    private void clampPropScroll() {
        Block block = this.stateEditing;
        if (block == null) {
            this.propScroll = 0.0D;
            return;
        }
        int count = block.getStateManager().getProperties().size();
        int viewHeight = (this.py + this.ph - 30) - (this.py + 34);
        double max = Math.max(0.0D, count * (double) PROP_ROW_H - viewHeight);
        this.propScroll = MathHelper.clamp(this.propScroll, 0.0D, max);
    }

    private void drawStateEditor(DrawContext context, int mouseX, int mouseY) {
        Block block = this.stateEditing;
        BlockState state = this.selectedStates.getOrDefault(block, block.getDefaultState());

        context.fill(this.px, this.py, this.px + this.pw, this.py + this.ph, COL_PANEL_BG);
        context.drawBorder(this.px, this.py, this.pw, this.ph, COL_PANEL_BORDER);

        context.drawText(this.textRenderer, Text.translatable("wes.state.title", block.getName()),
                this.px + 6, this.py + 8, 0xFFFFFFFF, false);

        String suffix = stateSuffix(state);
        context.drawText(this.textRenderer,
                Text.literal(suffix.isEmpty() ? "minecraft:" + Registries.BLOCK.getId(block).getPath() : suffix),
                this.px + 6, this.py + 20, COL_TEXT_DIM, false);

        int listTop = this.py + 34;
        int listBottom = this.py + this.ph - 30;
        int rowX = this.px + 6;
        int rowW = this.pw - 12;
        List<Property<?>> properties = new ArrayList<>(block.getStateManager().getProperties());

        context.enableScissor(rowX, listTop, rowX + rowW, listBottom);
        for (int i = 0; i < properties.size(); i++) {
            int y = listTop + i * PROP_ROW_H - (int) this.propScroll;
            if (y + PROP_ROW_H < listTop || y > listBottom) {
                continue;
            }
            Property<?> property = properties.get(i);
            if (isInside(mouseX, mouseY, rowX, y, rowW, PROP_ROW_H - 1)) {
                context.fill(rowX, y, rowX + rowW, y + PROP_ROW_H - 1, COL_ROW_HOVER);
            }
            context.drawText(this.textRenderer, property.getName(), rowX + 4, y + 5, COL_TEXT, false);

            int leftX = rowX + rowW - 150;
            int rightX = rowX + rowW - 14;
            context.drawText(this.textRenderer, "<", leftX, y + 5, 0xFFFFD479, false);
            context.drawText(this.textRenderer, ">", rightX, y + 5, 0xFFFFD479, false);
            context.drawText(this.textRenderer, valueName(property, state), leftX + 10, y + 5, COL_TEXT_SELECTED, false);
        }
        context.disableScissor();

        int buttonY = this.py + this.ph - 26;
        drawMiniButton(context, Text.translatable("wes.state.reset"), this.px + this.pw - 200, buttonY, 90, mouseX, mouseY);
        drawMiniButton(context, Text.translatable("wes.back"), this.px + this.pw - 100, buttonY, 90, mouseX, mouseY);
    }

    private void drawMiniButton(DrawContext context, Text label, int x, int y, int w, int mouseX, int mouseY) {
        boolean hovered = isInside(mouseX, mouseY, x, y, w, 18);
        context.fill(x, y, x + w, y + 18, hovered ? 0xFF3F6B4A : 0xFF2A2A2A);
        context.drawBorder(x, y, w, 18, 0xFF555555);
        context.drawCenteredTextWithShadow(this.textRenderer, label, x + w / 2, y + 5, 0xFFEEEEEE);
    }

    private boolean clickStateEditor(double mouseX, double mouseY) {
        Block block = this.stateEditing;
        if (block == null) {
            return false;
        }
        BlockState state = this.selectedStates.getOrDefault(block, block.getDefaultState());

        int listTop = this.py + 34;
        int listBottom = this.py + this.ph - 30;
        int rowX = this.px + 6;
        int rowW = this.pw - 12;
        List<Property<?>> properties = new ArrayList<>(block.getStateManager().getProperties());

        for (int i = 0; i < properties.size(); i++) {
            int y = listTop + i * PROP_ROW_H - (int) this.propScroll;
            if (y + PROP_ROW_H < listTop || y > listBottom) {
                continue;
            }
            Property<?> property = properties.get(i);
            int leftX = rowX + rowW - 150;
            int rightX = rowX + rowW - 14;
            if (isInside(mouseX, mouseY, leftX - 3, y, 12, PROP_ROW_H)) {
                this.selectedStates.put(block, cycleProperty(state, property, -1));
                return true;
            }
            if (isInside(mouseX, mouseY, rightX - 3, y, 12, PROP_ROW_H)) {
                this.selectedStates.put(block, cycleProperty(state, property, 1));
                return true;
            }
        }

        int buttonY = this.py + this.ph - 26;
        if (isInside(mouseX, mouseY, this.px + this.pw - 200, buttonY, 90, 18)) {
            this.selectedStates.put(block, block.getDefaultState());
            return true;
        }
        if (isInside(mouseX, mouseY, this.px + this.pw - 100, buttonY, 90, 18)) {
            this.stateEditing = null;
            return true;
        }
        return true; // 面板内部其它区域，吃掉点击
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
