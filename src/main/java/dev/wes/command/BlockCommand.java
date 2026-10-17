package dev.wes.command;

/**
 * 面板支持的所有「用方块图案填充」的 WorldEdit 指令，以及它们的参数形态。
 *
 * <h2>参数顺序是照 WorldEdit 7.2 的方法签名核对的</h2>
 * <p>用 {@code javap} 反查 {@code com.sk89q.worldedit.command.RegionCommands}
 * 与 {@code GenerationCommands} 得到：</p>
 * <pre>
 *   set     (Actor, EditSession, Region, Pattern)                              → //set &lt;pattern&gt;
 *   replace (Actor, EditSession, Region, Mask, Pattern)                        → //replace &lt;from&gt; &lt;pattern&gt;
 *   overlay (Actor, EditSession, Region, Pattern)                              → //overlay &lt;pattern&gt;
 *   center  (Actor, EditSession, Region, Pattern)                              → //center &lt;pattern&gt;
 *   walls   (Actor, EditSession, Region, Pattern)                              → //walls &lt;pattern&gt;
 *   faces   (Actor, EditSession, Region, Pattern)                              → //faces &lt;pattern&gt;
 *   line    (Actor, EditSession, Region, Pattern, int, boolean)                → //line &lt;pattern&gt; [thickness]
 *   curve   (Actor, EditSession, Region, Pattern, int, boolean)                → //curve &lt;pattern&gt; [thickness]
 *   hollow  (Actor, EditSession, Region, int, Pattern)                         → //hollow [thickness] &lt;pattern&gt;
 *   sphere  (..., Pattern, List&lt;Double&gt; radius, boolean)                      → //sphere &lt;pattern&gt; &lt;radius&gt;
 *   hsphere (..., Pattern, List&lt;Double&gt; radius)                               → //hsphere &lt;pattern&gt; &lt;radius&gt;
 *   cyl     (..., Pattern, List&lt;Double&gt; radius, int height)                   → //cyl &lt;pattern&gt; &lt;radius&gt; [height]
 *   hcyl    (..., Pattern, List&lt;Double&gt; radius, int height)                   → //hcyl &lt;pattern&gt; &lt;radius&gt; [height]
 *   pyramid (..., Pattern, int size)                                          → //pyramid &lt;pattern&gt; &lt;size&gt;
 *   hpyramid(..., Pattern, int size)                                          → //hpyramid &lt;pattern&gt; &lt;size&gt;
 * </pre>
 *
 * <p>注意 {@code //forest} 和 {@code //flora} 看起来像方块指令，但它们的签名里
 * <b>根本没有 Pattern</b>（只收 density / 树种类），所以不能放进这个面板。</p>
 *
 * <p>加新指令只要在这里补一个枚举值，面板会自动多出对应的参数输入框。</p>
 */
public enum BlockCommand {

    SET("set"),
    REPLACE("replace"),
    OVERLAY("overlay"),
    CENTER("center"),
    WALLS("walls"),
    FACES("faces"),
    LINE("line"),
    CURVE("curve"),
    HOLLOW("hollow"),
    SPHERE("sphere"),
    HSPHERE("hsphere"),
    CYL("cyl"),
    HCYL("hcyl"),
    PYRAMID("pyramid"),
    HPYRAMID("hpyramid");

    private final String id;

    BlockCommand(String id) {
        this.id = id;
    }

    /** 指令名，不带斜杠，例如 {@code set}。 */
    public String id() {
        return this.id;
    }

    /** 聊天里用来触发的完整前缀，例如 {@code //set }。 */
    public String trigger() {
        return "//" + this.id + " ";
    }

    /** //replace 需要额外指定「被替换的方块」。 */
    public boolean needsSource() {
        return this == REPLACE;
    }

    /** 第一个附加参数的 lang key；null 表示这条指令没有附加参数。 */
    public String extra1Lang() {
        switch (this) {
            case LINE:
            case CURVE:
            case HOLLOW:
                return "wes.extra.thickness";
            case SPHERE:
            case HSPHERE:
            case CYL:
            case HCYL:
                return "wes.extra.radius";
            case PYRAMID:
            case HPYRAMID:
                return "wes.extra.size";
            default:
                return null;
        }
    }

    public String extra1Default() {
        switch (this) {
            case LINE:
            case CURVE:
            case HOLLOW:
                return "1";
            case SPHERE:
            case HSPHERE:
            case CYL:
            case HCYL:
            case PYRAMID:
            case HPYRAMID:
                return "5";
            default:
                return null;
        }
    }

    /**
     * 半径是不是「可以用逗号写多个值」。
     *
     * <p>{@code //sphere} / {@code //cyl} 的半径类型是 {@code List<Double>}，
     * 所以 {@code 3,5,7} 是合法的椭球写法，输入框要放行逗号。</p>
     */
    public boolean extra1List() {
        return this == SPHERE || this == HSPHERE || this == CYL || this == HCYL;
    }

    /** 第二个附加参数的 lang key（目前只有 //cyl / //hcyl 的高度）。 */
    public String extra2Lang() {
        return this == CYL || this == HCYL ? "wes.extra.height" : null;
    }

    public String extra2Default() {
        return this == CYL || this == HCYL ? "1" : null;
    }

    /** true 表示附加参数写在图案前面（只有 //hollow 是这种）。 */
    public boolean extraFirst() {
        return this == HOLLOW;
    }
}
