import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Генерирует растровые launcher-иконки без внешних зависимостей. */
public final class MakeIcon {

    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final int[] SIZES = {48, 72, 96, 144, 192};

    public static void main(String[] args) throws Exception {
        File res = new File(args.length > 0 ? args[0] : "app/src/main/res");
        for (int i = 0; i < SIZES.length; i++) {
            File dir = new File(res, "mipmap-" + DENSITIES[i]);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Cannot create " + dir);
            }
            ImageIO.write(draw(SIZES[i], false), "png", new File(dir, "ic_launcher.png"));
            ImageIO.write(draw(SIZES[i], true), "png", new File(dir, "ic_launcher_round.png"));
        }
        System.out.println("Icons written to " + res);
    }

    private static BufferedImage draw(int size, boolean round) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        float pad = size * 0.015f;
        GradientPaint gradient = new GradientPaint(
                0, 0, new Color(0xFF4058),
                size, size, new Color(0xC6190E));
        g.setPaint(gradient);
        if (round) {
            double inset = size * 0.02;
            g.fill(new Ellipse2D.Double(inset, inset, size - inset * 2, size - inset * 2));
        } else {
            double radius = size * 0.22;
            g.fill(new RoundRectangle2D.Double(pad, pad, size - pad * 2, size - pad * 2,
                    radius, radius));
        }

        // Восьмая нота.
        double cx = size * 0.385;
        double cy = size * 0.675;
        double r = size * 0.115;
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));

        double stemX = cx + r * 0.62;
        double stemW = size * 0.062;
        double stemTop = size * 0.255;
        double stemBottom = cy;
        g.fill(new RoundRectangle2D.Double(stemX, stemTop, stemW,
                stemBottom - stemTop, stemW, stemW));

        Path2D flag = new Path2D.Double();
        flag.moveTo(stemX + stemW, stemTop);
        flag.curveTo(stemX + size * 0.23, stemTop + size * 0.015,
                stemX + size * 0.24, stemTop + size * 0.15,
                stemX + size * 0.155, stemTop + size * 0.19);
        flag.curveTo(stemX + size * 0.20, stemTop + size * 0.10,
                stemX + size * 0.14, stemTop + size * 0.075,
                stemX + stemW, stemTop + size * 0.075);
        flag.closePath();
        g.fill(flag);

        // Мягкий блик сверху.
        g.setColor(new Color(255, 255, 255, 22));
        g.fill(new Ellipse2D.Double(-size * 0.2, -size * 0.55,
                size * 1.1, size * 0.9));

        g.dispose();
        return image;
    }
}
