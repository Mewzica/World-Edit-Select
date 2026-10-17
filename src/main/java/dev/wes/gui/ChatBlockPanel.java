package dev.wes.gui;

import dev.wes.command.BlockCommand;
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
 * <p>确认时不会发送任何东西，只把拼好的指令（例如 {@code //set 50%minecraft:stone,...}
 * 或 {@code //replace minecraft:dirt 50%minecraft:stone,...}）写回聊天输入框，
 * 玩家自己看一眼再按回车，仍然可以继续手改。</p>
 */
public final class ChatBlockPanel {

    /* ------------------------------ 常量 ------------------------------ */

    private static final int ROW_H = 20;
    private static final int SEL_ROW_H = 24;
    private static final int FIELD_W = 38;
    /** 附加参数框比百分比框宽一点：半径要放得下 {@code 3,5,7} 这种椭球写法。 */
    private static final int EXTRA_W = 54;
    private static final int FIELD_H = 16;
    private static final int STATE_BTN_W = 18;
    private static final int PROP_ROW_H = 18;
    private static final double SCROLL_STEP = 16.0D;

    private static final int COL_PANEL_BG = 0xF0101010;
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
    /** //replace 的「被替换方块」选择按钮（只有 replace 才有）。 */
    private ButtonWidget sourceButton;
    /** 附加参数输入框：厚度 / 半径 / 高度 / 尺寸 / 密度，按指令决定有没有。 */
    private TextFieldWidget extra1Field;
    private TextFieldWidget extra2Field;

    /** 本次打开面板对应的指令，确认时按它的签名拼指令。 */
    private BlockCommand command = BlockCommand.SET;
    /** //replace 的源方块；null 表示还没选。 */
    private Block sourceBlock;
    /** true 表示下一次点左侧列表是「选源方块」而不是「加入图案」。 */
    private boolean pickingSource;

    private boolean open;
    private String query = "";
    private String statusMessage = "";
    /** 本帧要画的悬停提示（目前只有「态」按钮用），null 表示没有。 */
    private Text hoverTip;
    private double listScroll;
    private double selScroll;

    /* ------------------------------ 几何 ------------------------------ */

    private int px, py, pw, ph;
    private int listX, listY, listW, listH;
    private int selX, selY, selW, selH;

    /* 参数行（只在指令需要额外参数时才占高度） */
    private static final int OPT_H = 18;
    private int optY;
    private int sourceBtnX, sourceBtnW;
    private int extra1LabelX, extra1X, extra2LabelX, extra2X;
    private Text extra1Label, extra2Label;

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

    /**
     * 按指定指令打开面板。
     *
     * @param command 触发面板的那条 WorldEdit 指令，决定面板上要显示哪些参数框
     */
    public void open(BlockCommand command) {
        if (this.open) {
            return;
        }
        if (this.allBlocks.isEmpty()) {
            this.allBlocks.addAll(BlockCatalog.collectAll());
        }
        this.command = command == null ? BlockCommand.SET : command;
        this.open = true;
        this.statusMessage = "";
        this.query = "";
        this.listScroll = 0.0D;
        this.selScroll = 0.0D;
        this.stateEditing = null;
        this.propScroll = 0.0D;
        this.sourceBlock = null;
        this.pickingSource = false;
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
        this.pickingSource = false;
        this.searchField = null;
        this.confirmButton = null;
        this.cancelButton = null;
        this.sourceButton = null;
        this.extra1Field = null;
        this.extra2Field = null;
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
        String[] extras = this.captureExtras();
        this.computeLayout();
        this.buildWidgets();
        this.restoreExtras(extras);
        if (focused != null) {
            this.setFocus(this.searchField);
        }
    }

    /**
     * 重建控件前把用户填的附加参数抄下来。
     *
     * <p>{@link #buildWidgets()} 是「全量重建」，附加参数框会被重新填成默认值；
     * 而「加/减一个方块」也会触发重建，如果不抄一份，玩家填好的半径、厚度
     * 在点一下左侧列表后就没了。</p>
     */
    private String[] captureExtras() {
        return new String[]{
                this.extra1Field == null ? null : this.extra1Field.getText(),
                this.extra2Field == null ? null : this.extra2Field.getText()
        };
    }

    private void restoreExtras(String[] saved) {
        if (saved == null) {
            return;
        }
        if (this.extra1Field != null && saved[0] != null) {
            this.extra1Field.setText(saved[0]);
        }
        if (this.extra2Field != null && saved[1] != null) {
            this.extra2Field.setText(saved[1]);
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

        // 标题行占 20px；只有 //replace / //line / //sphere 这类需要额外参数的指令
        // 才多留一行参数行，//set、//walls 这些保持原来的紧凑布局。
        int titleBottom = this.py + 22;
        this.optY = titleBottom;
        int contentTop = titleBottom + (hasOptions() ? OPT_H + 2 : 0);

        int searchY = contentTop + 2;
        int bottom = this.py + this.ph - 30; // 底部留给按钮
        int innerW = this.pw - 18;

        this.listX = this.px + 6;
        this.listW = Math.max(120, innerW * 58 / 100);
        this.listY = searchY + 20;           // 搜索框高 16 + 4 间距
        this.listH = Math.max(30, bottom - this.listY);

        this.selX = this.listX + this.listW + 6;
        this.selW = Math.max(100, this.px + this.pw - 6 - this.selX);
        this.selY = this.listY;
        this.selH = this.listH;

        // 参数行内部按需从左往右排：源方块按钮 → 参数1 → 参数2
        int x = this.px + 6;
        this.sourceBtnX = x;
        this.sourceBtnW = 0;
        if (this.command.needsSource()) {
            this.sourceBtnW = 150;
            x += this.sourceBtnW + 10;
        }
        this.extra1Label = this.command.extra1Lang() == null ? null : Text.translatable(this.command.extra1Lang());
        this.extra1LabelX = x;
        this.extra1X = x;
        if (this.extra1Label != null) {
            this.extra1X = x + this.textRenderer.getWidth(this.extra1Label) + 4;
            x = this.extra1X + EXTRA_W + 14;
        }
        this.extra2Label = this.command.extra2Lang() == null ? null : Text.translatable(this.command.extra2Lang());
        this.extra2LabelX = x;
        this.extra2X = x;
        if (this.extra2Label != null) {
            this.extra2X = x + this.textRenderer.getWidth(this.extra2Label) + 4;
        }
    }

    /** 这条指令是否需要一行额外的参数控件。 */
    private boolean hasOptions() {
        return this.command.needsSource() || this.command.extra1Lang() != null;
    }

    private void buildWidgets() {
        this.setFocus(null);
        this.percentWidgets.clear();
        this.percentOrder.clear();
        this.sourceButton = null;
        this.extra1Field = null;
        this.extra2Field = null;

        // 参数行：源方块按钮 + 数字输入框，按当前指令的签名决定建哪几个
        if (this.command.needsSource()) {
            this.sourceButton = ButtonWidget.builder(this.sourceLabel(), b -> {
                this.pickingSource = !this.pickingSource;
                b.setMessage(this.sourceLabel());
            }).dimensions(this.sourceBtnX, this.optY, this.sourceBtnW, 16).build();
        }
        this.extra1Field = this.makeExtraField(this.extra1X, this.command.extra1Default(), this.command.extra1List());
        this.extra2Field = this.makeExtraField(this.extra2X, this.command.extra2Default(), false);

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

    /**
     * 建一个「纯数字」的附加参数输入框。
     *
     * @param defaultValue null 表示这条指令不需要这个参数，直接不建
     * @param allowList    true 时允许逗号（{@code //sphere} 的椭球半径 {@code 3,5,7}）
     */
    private TextFieldWidget makeExtraField(int x, String defaultValue, boolean allowList) {
        if (defaultValue == null) {
            return null;
        }
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, this.optY, EXTRA_W, FIELD_H, Text.literal(""));
        field.setMaxLength(11);
        field.setTextPredicate(text -> text.isEmpty() || text.matches(allowList ? "[0-9,]{1,11}" : "[0-9]{1,3}"));
        field.setText(defaultValue);
        field.setChangedListener(text -> this.statusMessage = "");
        return field;
    }

    /** //replace 那个按钮上显示的文字：没选 / 正在选 / 已选方块名。 */
    private Text sourceLabel() {
        if (this.sourceBlock == null) {
            return Text.translatable(this.pickingSource ? "wes.source.picking" : "wes.source.none");
        }
        Text label = Text.translatable("wes.source", this.sourceBlock.getName());
        return this.pickingSource ? label.copy().formatted(Formatting.YELLOW) : label;
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

        this.hoverTip = null;
        this.drawPanels(context, mouseX, mouseY);

        // 参数行控件（同样没注册进 Screen，手动画）
        if (this.sourceButton != null) {
            this.sourceButton.render(context, mouseX, mouseY, delta);
        }
        if (this.extra1Field != null) {
            this.extra1Field.render(context, mouseX, mouseY, delta);
        }
        if (this.extra2Field != null) {
            this.extra2Field.render(context, mouseX, mouseY, delta);
        }

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

        if (this.hoverTip != null) {
            context.drawTooltip(this.textRenderer, this.hoverTip, mouseX, mouseY);
        }
    }

    /**
     * 画「方块状态」按钮：三根滑杆的图标，用 fill 手绘。
     *
     * <p>之前这里是一个「态」字，CJK 字形在 16px 的框里又挤又偏下，
     * 而且英文环境下没意义。手绘图标不受字体影响，尺寸也精确可控。</p>
     */
    private static void drawStateButton(DrawContext context, int x, int y, boolean hovered) {
        context.fill(x, y, x + STATE_BTN_W, y + 16, hovered ? 0xFF3F6B4A : 0xFF2A2A2A);
        context.drawBorder(x, y, STATE_BTN_W, 16, hovered ? 0xFF9BE3AE : 0xFF555555);

        int left = x + 4;
        int width = STATE_BTN_W - 8;
        for (int i = 0; i < 3; i++) {
            int lineY = y + 4 + i * 4;
            context.fill(left, lineY, left + width, lineY + 1, 0xFF9A9A9A);
            int knobX = left + (i == 0 ? 1 : i == 1 ? 6 : 3);
            context.fill(knobX, lineY - 1, knobX + 2, lineY + 2, 0xFFE8E8E8);
        }
    }

    private void drawPanels(DrawContext context, int mouseX, int mouseY) {
        context.fill(this.px, this.py, this.px + this.pw, this.py + this.ph, COL_PANEL_BG);

        context.drawText(this.textRenderer, this.title(), this.px + 6, this.py + 8, 0xFFFFFFFF, false);

        // 右上角标出这次触发的是哪条指令（//set、//replace、//sphere …），
        // 免得玩家在面板里选了半天才发现指令不是自己想要的。
        String cmd = this.command.trigger().trim();
        context.drawText(this.textRenderer, cmd,
                this.px + this.pw - 6 - this.textRenderer.getWidth(cmd), this.py + 8, COL_TEXT_SELECTED, false);

        context.drawText(this.textRenderer,
                Text.translatable("wes.selected", this.selectedBlocks.size()),
                this.selX, this.listY - 16, COL_TEXT_DIM, false);

        // 参数行的文字标签（输入框和按钮自己会画）
        if (this.extra1Label != null) {
            context.drawText(this.textRenderer, this.extra1Label,
                    this.extra1LabelX, this.optY + 4, COL_TEXT, false);
        }
        if (this.extra2Label != null) {
            context.drawText(this.textRenderer, this.extra2Label,
                    this.extra2LabelX, this.optY + 4, COL_TEXT, false);
        }

        this.drawBlockList(context, mouseX, mouseY);
        this.drawSelectedList(context, mouseX, mouseY);
        this.drawScrollbars(context);

        if (!this.statusMessage.isEmpty()) {
            context.drawText(this.textRenderer, Text.literal(this.statusMessage),
                    this.px + 6, this.py + this.ph - 20, COL_ERROR, false);
        } else if (this.pickingSource) {
            context.drawText(this.textRenderer, Text.translatable("wes.hint.source"),
                    this.px + 6, this.py + this.ph - 20, 0xFFFFD479, false);
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
            // 当前 //replace 的源方块：左侧一条黄边标出来
            if (block == this.sourceBlock) {
                context.fill(this.listX, y, this.listX + 2, y + ROW_H - 1, 0xFFFFD479);
            }

            context.drawItem(new ItemStack(block), this.listX + 2, y + 2);
            String name = this.textRenderer.trimToWidth(block.getName().getString(), this.listW - 26);
            int color = block == this.sourceBlock ? 0xFFFFD479 : chosen ? COL_TEXT_SELECTED : COL_TEXT;
            context.drawText(this.textRenderer, name, this.listX + 22, y + 6, color, false);
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
                drawStateButton(context, bx, by, hover);
                if (hover) {
                    this.hoverTip = Text.translatable("wes.state.button");
                }
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
        if (this.sourceButton != null && this.sourceButton.mouseClicked(mouseX, mouseY, button)) {
            return true; // ButtonWidget 内部已经切了 pickingSource
        }
        if (this.extra1Field != null && this.extra1Field.mouseClicked(mouseX, mouseY, button)) {
            this.setFocus(this.extra1Field);
            return true;
        }
        if (this.extra2Field != null && this.extra2Field.mouseClicked(mouseX, mouseY, button)) {
            this.setFocus(this.extra2Field);
            return true;
        }
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

        // 2) 左侧列表：加入 / 移出（replace 正在选源方块时则是「设为源」）
        if (isInside(mouseX, mouseY, this.listX, this.listY, this.listW, this.listH)) {
            int index = (int) ((mouseY - this.listY + this.listScroll) / ROW_H);
            if (index >= 0 && index < this.filtered.size()) {
                Block block = this.filtered.get(index);
                if (this.pickingSource) {
                    this.sourceBlock = block;
                    this.pickingSource = false;
                    if (this.sourceButton != null) {
                        this.sourceButton.setMessage(this.sourceLabel());
                    }
                    this.statusMessage = "";
                    return true;
                }
                this.toggle(block);
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

        String[] extras = this.captureExtras();
        this.buildWidgets();
        this.restoreExtras(extras);

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
     * 确认：补齐百分比 → 按当前指令的签名拼指令 → 写回聊天输入框。
     *
     * <p>百分比规则：填了数字的照用；留空的均分剩余（余数从前往后 +1）；
     * 总和超过 100 直接报错。</p>
     *
     * <p>参数顺序按 {@link BlockCommand} 描述的真实签名来，例如：</p>
     * <ul>
     *   <li>{@code //set <pattern>}</li>
     *   <li>{@code //replace <from> <pattern>}</li>
     *   <li>{@code //hollow <thickness> <pattern>}（参数在前）</li>
     *   <li>{@code //cyl <pattern> <radius> [height]}</li>
     * </ul>
     */
    private void confirm() {
        if (this.selectedBlocks.isEmpty()) {
            this.statusMessage = Text.translatable("wes.err.empty").getString();
            return;
        }
        // //replace 必须先知道要替换掉什么，否则拼出来是残废指令
        if (this.command.needsSource() && this.sourceBlock == null) {
            this.statusMessage = Text.translatable("wes.err.source").getString();
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

        // 按指令签名拼参数：replace 的 <from> 永远在最前；hollow 的厚度在图案前；
        // 其余（line / sphere / cyl / pyramid / forest）参数都在图案后面。
        List<String> args = new ArrayList<>();
        if (this.command.needsSource()) {
            args.add(Registries.BLOCK.getId(this.sourceBlock).toString());
        }
        String extra1 = readNumber(this.extra1Field);
        String extra2 = readNumber(this.extra2Field);
        if (this.command.extraFirst()) {
            if (extra1 != null) {
                args.add(extra1);
            }
            args.add(body);
        } else {
            args.add(body);
            if (extra1 != null) {
                args.add(extra1);
            }
        }
        if (extra2 != null) {
            args.add(extra2);
        }

        String result = "//" + this.command.id() + " " + String.join(" ", args);
        if (result.length() > 240) {
            this.statusMessage = Text.translatable("wes.err.long", result.length()).getString();
            return;
        }

        // 只回填，不发送 —— 玩家自己按回车，还能继续改
        this.chatField.setText(result);
        this.chatField.setCursorToEnd();
        this.close();
    }

    /** 读一个可选数字参数；空串当作「没填」，拼指令时整段省略。 */
    private static String readNumber(TextFieldWidget field) {
        if (field == null) {
            return null;
        }
        String text = field.getText().trim();
        return text.isEmpty() ? null : text;
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
