package dev.wes.mixin;

import dev.wes.chat.ChatPanelHolder;
import dev.wes.chat.ChatPanelHooks;
import dev.wes.chat.ChatToggle;
import dev.wes.chat.ChatTrigger;
import dev.wes.command.BlockCommand;
import dev.wes.gui.ChatBlockPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 混入 {@link ChatScreen}：把方块选择面板挂在聊天界面上。
 *
 * <h2>为什么不用 Screen 切换</h2>
 * <p>面板是聊天界面里的浮层（{@link ChatBlockPanel}），所以这里只做四件事：
 * 渲染转发、鼠标转发、按键转发、窗口尺寸变化后重新摆位。</p>
 *
 * <h2>为什么 render 上挂了两个注入</h2>
 * <p>HEAD 那个是「轮询输入框」——Forge 的 {@code EditBox} 没有一个既能覆盖
 * 打字又能覆盖粘贴的公共回调，而 {@code ChatScreen} 只在聊天打开时渲染，
 * 在这里每帧看一眼输入内容是最稳的做法（详见 {@link ChatTrigger}）。
 * TAIL 那个才是画面板。</p>
 *
 * <h2>为什么字符输入不用管</h2>
 * <p>1.20.1 的 {@code ChatScreen} 没有重写 {@code charTyped}，而 {@code Screen}
 * 从 {@code ContainerEventHandler} 继承来的默认实现是「转发给当前焦点控件」。
 * 面板里的输入框只要用 {@code setFocused} 拿到焦点，打字就会自动进去。</p>
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin implements ChatPanelHolder {

    @Shadow
    protected EditBox input;

    @Unique
    private ChatBlockPanel wes$panel;

    /* ============================ 面板持有 ============================ */

    @Override
    public ChatBlockPanel wes$panel() {
        if (this.wes$panel == null) {
            // font 是 Screen 的字段，不是 ChatScreen 自己的：
            // 用 @Shadow 会触发 "Cannot find target for @Shadow field"
            // （注解处理器只查目标类本身），所以直接从客户端实例取。
            this.wes$panel = new ChatBlockPanel((ChatScreen) (Object) this,
                    Minecraft.getInstance().font, this.input);
        }
        return this.wes$panel;
    }

    @Override
    public void wes$openPanel(BlockCommand command) {
        this.wes$panel().open(command);
    }

    @Override
    public boolean wes$isPanelOpen() {
        return this.wes$panel != null && this.wes$panel.isOpen();
    }

    /* ============================ 注入点 ============================ */

    /** 窗口尺寸变化会重建 input，面板要跟着重新摆位。 */
    @Inject(method = "init", at = @At("TAIL"))
    private void wes$onInit(CallbackInfo ci) {
        if (this.wes$panel != null) {
            this.wes$panel.onResize(this.input);
        }
    }

    /** 每帧看一眼输入框：打字、粘贴、输入法上屏都会让内容变化。 */
    @Inject(method = "render", at = @At("HEAD"))
    private void wes$pollInput(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ChatTrigger.check((ChatScreen) (Object) this, this.input.getValue());
    }

    /**
     * 画在最后：此时聊天输入框和它的补全提示都已经画完了，
     * 面板铺在它们上方，而底部的输入行仍然露出来。
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void wes$onRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ChatPanelHooks.render(this, graphics, mouseX, mouseY, partialTick);
        // 开关单独画：面板关着的时候它也要在，而且必须压在最上层
        ChatToggle.render(graphics, this.input, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void wes$onMouseClicked(double mouseX, double mouseY, int button,
                                    CallbackInfoReturnable<Boolean> cir) {
        // 先判开关：它在面板之外，不能被「点面板外面就收起」那条逻辑吃掉
        if (ChatToggle.mouseClicked(this.input, mouseX, mouseY, button)) {
            if (!ChatToggle.isEnabled() && this.wes$isPanelOpen()) {
                // 关掉开关时把已经开着的面板一并收起来，免得它悬在那没法再触发
                this.wes$panel().close();
            }
            cir.setReturnValue(true);
            return;
        }
        if (ChatPanelHooks.mouseClicked(this, mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 1.20.2 起 {@code mouseScrolled} 多了一个横向滚动量（{@code scrollX}），
     * 注入方法的参数表必须和目标方法逐一对齐，否则 Mixin 在加载期就会抛
     * {@code InvalidInjectionException}。面板只关心纵向，所以只把 {@code scrollY}
     * 递给它。
     */
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void wes$onMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (ChatPanelHooks.mouseScrolled(this, mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wes$onKeyPressed(int keyCode, int scanCode, int modifiers,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (ChatPanelHooks.keyPressed(this, keyCode, scanCode, modifiers)) {
            cir.setReturnValue(true);
            return;
        }
        if (this.wes$isPanelOpen()) {
            // 面板开着时不让按键漏给原版聊天框（比如回车会被当成发送消息）
            cir.setReturnValue(true);
            return;
        }

        // 面板没开时的兜底：文本正好停在 "//set " / "//replace " / ... 又按了回车，
        // 别把这半截指令当命令发出去，改成打开面板。
        // 开关关掉时这条兜底也要让路：玩家就是想把这半截指令原样发出去。
        if (ChatToggle.isEnabled()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && ChatTrigger.matches(this.input.getValue())) {
            ChatTrigger.check((ChatScreen) (Object) this, this.input.getValue());
            cir.setReturnValue(true);
        }
    }
}
