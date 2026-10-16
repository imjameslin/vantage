import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File;
public class Crop { public static void main(String[] a) throws Exception {
  int x=Integer.parseInt(a[2]),y=Integer.parseInt(a[3]),w=Integer.parseInt(a[4]),h=Integer.parseInt(a[5]),s=Integer.parseInt(a[6]);
  BufferedImage[] im={ImageIO.read(new File(a[0])),ImageIO.read(new File(a[1]))};
  BufferedImage o=new BufferedImage(w*s*3+20,h*s,BufferedImage.TYPE_INT_RGB);
  for(int k=0;k<2;k++) for(int j=0;j<h*s;j++) for(int i=0;i<w*s;i++) o.setRGB(k*(w*s+10)+i,j,im[k].getRGB(x+i/s,y+j/s));
  for(int j=0;j<h*s;j++) for(int i=0;i<w*s;i++){int p=im[0].getRGB(x+i/s,y+j/s),q=im[1].getRGB(x+i/s,y+j/s);int d=0;for(int t=0;t<24;t+=8)d=Math.max(d,Math.abs(((p>>t)&255)-((q>>t)&255)));int v=Math.min(255,d*12);o.setRGB(2*(w*s+10)+i,j,(v<<16));}
  ImageIO.write(o,"png",new File(a[7]));
}}
