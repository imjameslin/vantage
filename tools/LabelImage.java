import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Stage 3 r6: burns a caption into a screenshot (bottom-left, white on a dark bar), leaving the FPS line at the top untouched.
 * usage: java tools/LabelImage.java <in.png> <out.png> <caption>
 */
public final class LabelImage {
    public static void main(final String[] args) throws Exception {
        BufferedImage in = ImageIO.read(new File(args[0]));
        BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(in, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int size = Math.max(18, in.getHeight() / 32);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
        int w = g.getFontMetrics().stringWidth(args[2]) + size;
        int h = size + size / 2;
        int y = in.getHeight() - h - size / 2;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(size / 2, y, w, h);
        g.setColor(Color.WHITE);
        g.drawString(args[2], size, y + size + size / 8);
        g.dispose();
        ImageIO.write(out, "png", new File(args[1]));
    }
}
