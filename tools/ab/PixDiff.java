import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File;
public class PixDiff { public static void main(String[] a) throws Exception {
  BufferedImage x=ImageIO.read(new File(a[0])), y=ImageIO.read(new File(a[1]));
  int n=0,minx=99999,miny=99999,maxx=-1,maxy=-1,maxd=0;
  for(int j=0;j<x.getHeight();j++) for(int i=0;i<x.getWidth();i++){int p=x.getRGB(i,j),q=y.getRGB(i,j); int d=0; for(int s=0;s<24;s+=8) d=Math.max(d,Math.abs(((p>>s)&255)-((q>>s)&255))); if(d>0){n++; if(d>maxd)maxd=d; if(d>16){minx=Math.min(minx,i);miny=Math.min(miny,j);maxx=Math.max(maxx,i);maxy=Math.max(maxy,j);}}}
  System.out.println(a[1]+": pixels differing "+n+", max delta "+maxd+", >16 bbox "+minx+","+miny+" - "+maxx+","+maxy);
}}
