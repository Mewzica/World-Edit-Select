package dev.wes.mixin;

import dev.wes.chat.ChatPanelHolder;
import dev.wes.chat.ChatPanelHooks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ParentElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 兼容补丁：让面板在 AtomChat 的自定义聊天界面上也能显示和操作。
 *
 * <h2>为什么原版注入点失效</h2>
 * <p>AtomChat 用一个 {@code AtomChatScreen}（继承 {@code ChatScreen}）整体替换了
 * 聊天界面，而且它的 {@code render} <b>故意不调用 {@code super.render}</b>
 * —— 源码里写得很清楚：调了 super 就会把原版输入框也画出来。
 * 所以 WES 挂在 {@code ChatScreen#render} 末尾的注入对它永远不会执行，
 * 面板开了却一片空白。</p>
 *
 * <p>输入同理：它的 {@code mouseClicked} / {@code keyPressed} / {@code charTyped}
 * 都先交给自己内部的 gate 处理，只有 gate 放行时才调 {@code super}，
 * 面板根本抢不到点击和键盘。</p>
 *
 * <h2>为什么方法名是 method_xxxxx</h2>
 * <p>{@code AtomChatScreen} 是模组类，但它重写的是 MC 的方法，所以打进 jar 时
 * 会被 tiny-remapper 改名成 intermediary 名。用 {@code targets = "..."} 指向模组类时，
 * Mixin 的注解处理器查不到这个类，生成不了 refmap，写 {@code "render"} 反而匹配不上。
 * 这里直接写运行时真名（已用 javap 从 AtomChat 的生产 jar 里核对过），
 * 并加 {@code remap = false} 让 Loom 别去动它。</p>
 *
 * <h2>为什么全部 require = 0</h2>
 * <p>这是对第三方模组内部实现的补丁，AtomChat 换版本时方法可能变。
 * 匹配不上时应该「面板不显示」而不是「游戏直接崩」，所以一个都不强制。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screen.AtomChatScreen", remap = false)
public abstract class AtomChatScreenMixin {

    /** {@code Screen#render}，运行时名 method_25394。 */
    @Inject(method = "method_25394", at = @At("TAIL"), require = 0)
    private void wes$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ChatPanelHooks.render((ChatPanelHolder) (Object) this, context, mouseX, mouseY, delta);
    }

    /** {@code Screen#mouseClicked}，运行时名 method_25402。 */
    @Inject(method = "method_25402", at = @At("HEAD"), cancellable = true, require = 0)
    private void wes$mouseClicked(double mouseX, double mouseY, int button,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (ChatPanelHooks.mouseClicked((ChatPanelHolder) (Object) this, mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    /** {@code Screen#mouseScrolled}，运行时名 method_25401。 */
    @Inject(method = "method_25401", at = @At("HEAD"), cancellable = true, require = 0)
    private void wes$mouseScrolled(double mouseX, double mouseY, double amount,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (ChatPanelHooks.mouseScrolled((ChatPanelHolder) (Object) this, mouseX, mouseY, amount)) {
            cir.setReturnValue(true);
        }
    }

    /** {@code Screen#keyPressed}，运行时名 method_25404。 */
    @Inject(method = "method_25404", at = @At("HEAD"), cancellable = true, require = 0)
    private void wes$keyPressed(int keyCode, int scanCode, int modifiers,
                                CallbackInfoReturnable<Boolean> cir) {
        ChatPanelHolder holder = (ChatPanelHolder) (Object) this;
        if (!holder.wes$isPanelOpen()) {
            return;
        }
        // 面板开着时先把按键交给面板，剩下的也不再往下传，
        // 否则 AtomChat 会把它当成自己的快捷键（回车发消息之类）。
        ChatPanelHooks.keyPressed(holder, keyCode, scanCode, modifiers);
        cir.setReturnValue(true);
    }

    /** {@code Screen#charTyped}，运行时名 method_25400。 */
    @Inject(method = "method_25400", at = @At("HEAD"), cancellable = true, require = 0)
    private void wes$charTyped(char chr, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (ChatPanelHooks.charTyped((ParentElement) (Object) this,
                (ChatPanelHolder) (Object) this, chr, modifiers)) {
            cir.setReturnValue(true);
        }
    }
}
