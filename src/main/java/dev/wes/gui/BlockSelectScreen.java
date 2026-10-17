package dev.wes.gui;

import dev.wes.worldedit.BlockCatalog;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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
 * 方块选择界面。
 *
 * <p>布局：左边「全部方块 + 搜索框」，右边「已选方块 + 百分比输入框」，底部确认/取消。</p>
 *
 * <h2>几个容易踩的坑（都已在代码里处理）</h2>
 * <ol>
 *   <li><b>只渲染可见行</b>：列表有 1000+ 个方块，全画会直接把帧率打死。
 *       这里按 {@code scroll / ROW_H} 算出首行下标，只遍历视口内的行，
 *       并且用 {@code enableScissor} 把超出面板的部分裁掉。</li>
 *   <li><b>右侧一堆 TextFieldWidget 的焦点</b>：选中/取消方块后控件要重建，
 *       重建时先记住「哪个方块对应的框有焦点、光标在哪」，重建完再还原，
 *       否则玩家每点一次就丢焦点，输入到一半的百分比直接作废。</li>
 *   <li><b>滚动后控件位置</b>：百分比框是真正的 Widget，不是画出来的，
 *       所以滚动时必须同步 {@code setX/setY}，并把滚出视口的设成不可见，
 *       否则它们会画到面板外面去。</li>
 * </ol>
 */
public class BlockSelectScreen extends Screen {

    /* ------------------------------ 常量 ------------------------------ */

    private static final int MARGIN = 12;
    private static final int ROW_H = 20;          // 左侧每行高度
    private static final int SEL_ROW_H = 24;      // 右侧每行高度
    private static final int FIELD_W = 38;        // 百分比输入框宽
    private static final int FIELD_H = 16;        // 百分比输入框高
    private static final int GAP = 10;            // 左右面板间距
    private static final double SCROLL_STEP = 16.0D;

    private static final int COL_PANEL_BG = 0xF0181818;
    private static final int COL_PANEL_BORDER = 0xFF3A3A3A;
    private static final int COL_ROW_HOVER = 0x33FFFFFF;
    private static final int COL_ROW_SELECTED = 0x2A2ECC71;
    private static final int COL_TEXT = 0xFFDDDDDD;
    private static final int COL_TEXT_SELECTED = 0xFF7BE08A;
    private static final int COL_TEXT_DIM = 0xFF888888;
    private static final int COL_ERROR = 0xFFFF6B6B;

    /* ------------------------------ 数据 ------------------------------ */

    /** 全部方块（懒加载一次，之后复用）。 */
    private final List<Block> allBlocks = new ArrayList<>();
    /** 搜索过滤后的结果，左侧真正渲染的列表。 */
    private final List<Block> filtered = new ArrayList<>();
    /** 已选方块 → 百分比。0 表示「留空，确认时均分」。 */
    private final Map<Block, Integer> selectedBlocks = new LinkedHashMap<>();
    /** 已选方块 → 对应的输入框（重建控件时整体替换）。 */
    private final Map<Block, TextFieldWidget> percentFields = new LinkedHashMap<>();

    private TextFieldWidget searchField;
    private String lastQuery = "";
    private String statusMessage = "";
    private boolean blocksLoaded;

    /* ------------------------------ 几何 ------------------------------ */

    private int listX, listY, listW, listH;
    private int selX, selY, selW, selH;
    private double listScroll, selScroll;
    /** 上一帧的鼠标坐标：1.20.1 的 renderBackground 拿不到，只能缓存。 */
    private int lastMouseX, lastMouseY;

    public BlockSelectScreen() {
        super(Text.translatable("wes.title"));
    }

    /* ============================== 初始化 ============================== */

    @Override
    protected void init() {
        super.init();

        // 重建前先清干净：clearChildren 只清列表，Screen 的 focused 引用还得自己断，
        // 否则会留着一个已经不在 children 里的旧控件。
        this.setFocused(null);
        this.clearChildren();
        this.percentFields.clear();

        if (!blocksLoaded) {
            this.allBlocks.addAll(BlockCatalog.collectAll());
            this.blocksLoaded = true;
        }

        computeLayout();
        createSearchField();
        createPercentFields();
        createButtons();

        this.applyFilter(this.lastQuery);
        this.layoutWidgets();

        // 打开界面时直接把焦点给搜索框，玩家可以立刻打字过滤
        if (this.lastQuery.isEmpty()) {
            this.setFocused(this.searchField);
        }
    }

    /** 纯数学的布局计算，不碰控件。 */
    private void computeLayout() {
        int top = 26;
        int bottom = this.height - 34;
        int usable = this.width - MARGIN * 2 - GAP;

        this.listX = MARGIN;
        this.listW = Math.max(120, usable * 58 / 100);
        this.listY = top + 22;                       // 上面 22px 留给搜索框
        this.listH = Math.max(40, bottom - this.listY);

        this.selX = this.listX + this.listW + GAP;
        this.selW = Math.max(100, this.width - MARGIN - this.selX);
        this.selY = this.listY;
        this.selH = this.listH;
    }

    private void createSearchField() {
        Text placeholder = Text.translatable("wes.search").formatted(Formatting.DARK_GRAY);
        this.searchField = this.addDrawableChild(
                new TextFieldWidget(this.textRenderer, this.listX, this.listY - 22, this.listW, 18,
                        Text.translatable("wes.search")));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(placeholder);
        this.searchField.setText(this.lastQuery);
        this.searchField.setChangedListener(query -> {
            this.lastQuery = query;
            this.applyFilter(query);
            this.listScroll = 0.0D;
            this.layoutWidgets();
        });
    }

    /**
     * 为每个已选方块建一个百分比输入框。
     *
     * <p>输入框只允许 0~3 位数字，且实时把解析结果写回 {@link #selectedBlocks}，
     * 这样即使玩家不点确认，右侧列表的状态也是自洽的。</p>
     */
    private void createPercentFields() {
        for (Map.Entry<Block, Integer> entry : this.selectedBlocks.entrySet()) {
            Block block = entry.getKey();
            TextFieldWidget field = this.addDrawableChild(
                    new TextFieldWidget(this.textRenderer, 0, 0, FIELD_W, FIELD_H, Text.literal("%")));
            field.setMaxLength(3);
            field.setTextPredicate(text -> text.isEmpty() || text.matches("[0-9]{1,3}"));
            int value = entry.getValue();
            field.setText(value <= 0 ? "" : Integer.toString(value));
            field.setChangedListener(text -> {
                int parsed = 0;
                if (!text.isEmpty()) {
                    try {
                        parsed = Integer.parseInt(text);
                    } catch (NumberFormatException ignored) {
                        // setTextPredicate 已经挡住了非数字，这里只是保险
                    }
                }
                this.selectedBlocks.put(block, MathHelper.clamp(parsed, 0, 100));
                this.statusMessage = "";
            });
            this.percentFields.put(block, field);
        }
    }

    private void createButtons() {
        int y = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("wes.confirm"), b -> this.confirm())
                .dimensions(this.width / 2 - 104, y, 100, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("wes.cancel"), b -> this.close())
                .dimensions(this.width / 2 + 4, y, 100, 20).build());
    }

    /* ============================== 过滤 / 滚动 ============================== */

    private void applyFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
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

    /** 把搜索框、百分比框摆到正确位置（滚动 / 搜索 / 选中变化后都要调一次）。 */
    private void layoutWidgets() {
        this.clampScroll();

        if (this.searchField != null) {
            this.searchField.setX(this.listX);
            this.searchField.setY(this.listY - 22);
            this.searchField.setWidth(this.listW);
        }

        int index = 0;
        for (TextFieldWidget field : this.percentFields.values()) {
            int rowTop = this.selY + 4 + index * SEL_ROW_H - (int) this.selScroll;
            field.setX(this.selX + this.selW - FIELD_W - 8);
            field.setY(rowTop + (SEL_ROW_H - FIELD_H) / 2 - 1);
            field.setWidth(FIELD_W);
            // 滚出视口的控件必须隐藏，否则会画到面板外面（Widget 不受 scissor 保护）
            field.visible = rowTop + SEL_ROW_H > this.selY && rowTop < this.selY + this.selH;
            index++;
        }
    }

    /* ============================== 渲染 ============================== */

    /**
     * 所有面板内容都放在 {@code renderBackground} 里画。
     *
     * <p>这是 1.20.1 最省事的分层方式：{@code super.render()} 会先调
     * {@code renderBackground()}、再渲染全部子控件。把面板画在 background 里，
     * 天然就是「背景 → 面板 → 输入框/按钮」的正确顺序，不用手动遍历 children。</p>
     *
     * <p>注意 1.20.1 的这个方法签名只有一个 {@link DrawContext} 参数
     * （1.20.2 之后才加上 mouseX/mouseY/delta），所以鼠标坐标得自己缓存。</p>
     */
    @Override
    public void renderBackground(DrawContext context) {
        context.fill(0, 0, this.width, this.height, 0xC8101010); // 全屏压暗
        this.drawPanels(context, this.lastMouseX, this.lastMouseY);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // renderBackground 拿不到鼠标坐标，先存下来给它用
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.render(context, mouseX, mouseY, delta); // background(面板) → 子控件
        this.drawScrollbars(context);
        this.drawHoverTooltip(context, mouseX, mouseY);
    }

    private void drawPanels(DrawContext context, int mouseX, int mouseY) {
        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFFFF);

        // 左面板
        context.fill(this.listX - 6, this.listY - 6,
                this.listX + this.listW + 6, this.listY + this.listH + 6, COL_PANEL_BG);
        context.drawBorder(this.listX - 6, this.listY - 6, this.listW + 12, this.listH + 12, COL_PANEL_BORDER);

        // 右面板
        context.fill(this.selX - 6, this.selY - 6,
                this.selX + this.selW + 6, this.selY + this.selH + 6, COL_PANEL_BG);
        context.drawBorder(this.selX - 6, this.selY - 6, this.selW + 12, this.selH + 12, COL_PANEL_BORDER);

        this.drawBlockList(context, mouseX, mouseY);
        this.drawSelectedList(context, mouseX, mouseY);

        // 右侧标题栏
        context.drawText(this.textRenderer,
                Text.translatable("wes.selected", this.selectedBlocks.size()),
                this.selX + 2, this.selY - 16, COL_TEXT_DIM, false);

        // 底部提示 / 错误
        if (!this.statusMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.statusMessage),
                    this.width / 2, this.height - 42, COL_ERROR);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("wes.hint"),
                    this.width / 2, this.height - 42, COL_TEXT_DIM);
        }
    }

    /** 左侧列表：只遍历视口内的行。 */
    private void drawBlockList(DrawContext context, int mouseX, int mouseY) {
        context.enableScissor(this.listX, this.listY, this.listX + this.listW, this.listY + this.listH);

        int first = Math.max(0, (int) (this.listScroll / ROW_H));
        int rows = this.listH / ROW_H + 2;                       // +2 是为了滚动时边缘不留空
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

            // 图标：ItemStack 渲染，方块没有对应物品时（火、水）这里会是空的，属正常
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

    /** 右侧列表：画行背景 / 图标 / 名字，输入框由 Widget 自己画在上面。 */
    private void drawSelectedList(DrawContext context, int mouseX, int mouseY) {
        context.enableScissor(this.selX, this.selY, this.selX + this.selW, this.selY + this.selH);

        int index = 0;
        for (Block block : this.selectedBlocks.keySet()) {
            int y = this.selY + 4 + index * SEL_ROW_H - (int) this.selScroll;
            index++;

            if (y + SEL_ROW_H < this.selY || y > this.selY + this.selH) {
                continue; // 不在视口内，跳过
            }

            boolean hovered = isInside(mouseX, mouseY, this.selX, y, this.selW, SEL_ROW_H - 2);
            context.fill(this.selX, y, this.selX + this.selW, y + SEL_ROW_H - 2,
                    hovered ? COL_ROW_HOVER : 0x18FFFFFF);

            context.drawItem(new ItemStack(block), this.selX + 4, y + 4);

            int nameWidth = this.selW - 24 - FIELD_W - 14;
            String name = this.textRenderer.trimToWidth(block.getName().getString(), Math.max(10, nameWidth));
            context.drawText(this.textRenderer, name, this.selX + 24, y + 8, 0xFFEEEEEE, false);
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

    private void drawHoverTooltip(DrawContext context, int mouseX, int mouseY) {
        if (!isInside(mouseX, mouseY, this.listX, this.listY, this.listW, this.listH)) {
            return;
        }
        int index = (int) ((mouseY - this.listY + this.listScroll) / ROW_H);
        if (index < 0 || index >= this.filtered.size()) {
            return;
        }
        Block block = this.filtered.get(index);
        context.drawTooltip(this.textRenderer, Text.literal(BlockCatalog.idOf(block)), mouseX, mouseY);
    }

    /* ============================== 交互 ============================== */

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // ---- 左键点左侧列表：加入 / 移出已选 ----
            if (isInside(mouseX, mouseY, this.listX, this.listY, this.listW, this.listH)) {
                int index = (int) ((mouseY - this.listY + this.listScroll) / ROW_H);
                if (index >= 0 && index < this.filtered.size()) {
                    this.toggle(this.filtered.get(index));
                }
                return true; // 空白区也吃掉，避免焦点乱跳
            }

            // ---- 左键点右侧列表：移除（但点在输入框上要交给 Widget 处理）----
            if (isInside(mouseX, mouseY, this.selX, this.selY, this.selW, this.selH)) {
                int index = (int) ((mouseY - this.selY - 4 + this.selScroll) / SEL_ROW_H);
                List<Block> order = new ArrayList<>(this.selectedBlocks.keySet());
                if (index >= 0 && index < order.size()) {
                    Block block = order.get(index);
                    TextFieldWidget field = this.percentFields.get(block);
                    if (field != null && field.visible
                            && mouseX >= field.getX() && mouseX < field.getX() + field.getWidth()
                            && mouseY >= field.getY() && mouseY < field.getY() + field.getHeight()) {
                        // 点的是百分比输入框：走原版逻辑，Screen 会自动把焦点切过去
                        return super.mouseClicked(mouseX, mouseY, button);
                    }
                    this.selectedBlocks.remove(block);
                    this.statusMessage = "";
                    this.rebuildWidgets();
                    return true;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInside(mouseX, mouseY, this.listX, this.listY, this.listW, this.listH)) {
            this.listScroll -= amount * SCROLL_STEP;
            this.clampScroll();
            return true;
        }
        if (isInside(mouseX, mouseY, this.selX, this.selY, this.selW, this.selH)) {
            this.selScroll -= amount * SCROLL_STEP;
            this.clampScroll();
            this.layoutWidgets(); // 输入框要跟着滚
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 回车 = 确认。文本框自己不吃回车，所以这里直接拦。
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false; // 单人游戏里不要把世界冻住
    }

    /* ============================== 内部逻辑 ============================== */

    private void toggle(Block block) {
        if (this.selectedBlocks.containsKey(block)) {
            this.selectedBlocks.remove(block);
        } else {
            this.selectedBlocks.put(block, 0);
        }
        this.statusMessage = "";
        this.rebuildWidgets();
    }

    /**
     * 重建全部控件，并还原焦点。
     *
     * <p>为什么整体重建而不是增量增删？因为「焦点 + 光标位置 + 滚动位置」
     * 三者耦合，增量维护极容易在某个分支漏掉一处，最后表现为「输入框点了没反应」
     * 或者「打字打一半跳走」。整体重建只有一处真相来源，反而更稳。</p>
     */
    private void rebuildWidgets() {
        Block focusedBlock = null;
        int cursor = 0;
        for (Map.Entry<Block, TextFieldWidget> entry : this.percentFields.entrySet()) {
            if (entry.getValue().isFocused()) {
                focusedBlock = entry.getKey();
                cursor = entry.getValue().getCursor();
                break;
            }
        }
        boolean searchFocused = this.searchField != null && this.searchField.isFocused();
        if (this.searchField != null) {
            this.lastQuery = this.searchField.getText();
        }

        this.init(); // 里面会 clearChildren 并重建搜索框/输入框/按钮

        if (focusedBlock != null) {
            TextFieldWidget field = this.percentFields.get(focusedBlock);
            if (field != null) {
                this.setFocused(field);
                field.setCursor(MathHelper.clamp(cursor, 0, field.getText().length()));
                return;
            }
        }
        if (searchFocused) {
            this.setFocused(this.searchField);
        }
    }

    /**
     * 确认：补齐百分比 → 拼 WorldEdit 指令 → 发送 → 关界面。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>填了数字的按原样用；</li>
     *   <li>留空（或 0）的均分剩余百分比，除不尽的把余数从前往后每块 +1；</li>
     *   <li>总和超过 100 直接报错，不动手；</li>
     *   <li>最终一个都没剩下（比如填满 100% 又有留空项）就报错。</li>
     * </ul>
     */
    private void confirm() {
        MinecraftClient client = this.client;
        if (client == null || client.player == null || client.player.networkHandler == null) {
            this.close();
            return;
        }
        if (this.selectedBlocks.isEmpty()) {
            this.statusMessage = Text.translatable("wes.err.empty").getString();
            return;
        }

        List<Block> order = new ArrayList<>(this.selectedBlocks.keySet());
        int[] percents = new int[order.size()];
        List<Integer> blanks = new ArrayList<>();
        int used = 0;

        for (int i = 0; i < order.size(); i++) {
            int value = this.readPercent(order.get(i));
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

        StringBuilder command = new StringBuilder("//set ");
        int written = 0;
        for (int i = 0; i < order.size(); i++) {
            if (percents[i] <= 0) {
                continue; // 没分到百分比的方块直接跳过，否则 WorldEdit 会报错
            }
            if (written++ > 0) {
                command.append(',');
            }
            command.append(percents[i]).append('%').append(Registries.BLOCK.getId(order.get(i)));
        }

        if (written == 0) {
            this.statusMessage = Text.translatable("wes.err.empty").getString();
            return;
        }

        String result = command.toString();
        // 原版 CommandC2SPacket 会把超过 256 字符的命令截断，超了就别发了
        if (result.length() > 240) {
            this.statusMessage = Text.translatable("wes.err.long", result.length()).getString();
            return;
        }

        // 注意：sendChatCommand 的参数「不带」前导斜杠，它自己会补。
        // 我们要发的是 WorldEdit 的 //set，所以这里必须保留两个斜杠。
        client.player.networkHandler.sendChatCommand(result);
        this.close();
    }

    private int readPercent(Block block) {
        TextFieldWidget field = this.percentFields.get(block);
        if (field == null || field.getText().isBlank()) {
            return 0;
        }
        try {
            return MathHelper.clamp(Integer.parseInt(field.getText().trim()), 0, 100);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
