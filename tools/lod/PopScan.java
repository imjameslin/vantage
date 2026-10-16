import java.awt.image.BufferedImage; import java.io.File; import javax.imageio.ImageIO; import java.util.*;
public class PopScan {
  static int[] lum(BufferedImage im){int w=im.getWidth(),h=im.getHeight();int[] p=im.getRGB(0,0,w,h,null,0,w);int[] l=new int[w*h];for(int i=0;i<p.length;i++){int c=p[i];l[i]=((c>>16&255)*3+(c>>8&255)*6+(c&255))/10;}return l;}
  public static void main(String[] a) throws Exception {
    File dir=new File(a[0]); int T=16; int N=a.length>1?Integer.parseInt(a[1]):3; File[] fs=dir.listFiles((d,n)->n.endsWith(".png")); Arrays.sort(fs);
    List<String> out=new ArrayList<>(); long total=0;
    for(int i=0;i+2<fs.length;i++){ if(i%N>N-3)continue; // frames 1-based: bursts (1,2,3),(4,5,6)... file 0 may be index 0001
      BufferedImage A=ImageIO.read(fs[i]),B=ImageIO.read(fs[i+1]),C=ImageIO.read(fs[i+2]); int w=A.getWidth(),h=A.getHeight();
      int[] la=lum(A),lb=lum(B),lc=lum(C); int tw=w/T,th=h/T; int pops=0; double worst=0; int wx=0,wy=0; double g1=0,g2=0; for(int k=0;k<w*600;k+=7){g1+=Math.abs(la[k]-lb[k]);g2+=Math.abs(lb[k]-lc[k]);} if(Math.max(g1,g2)>1.4*Math.min(g1,g2)){out.add(fs[i].getName()+" hitch");continue;}
      for(int ty=0;ty<th;ty++)for(int tx=0;tx<tw;tx++){ if(tx*T>1050&&ty*T>640)continue; if(ty*T>880)continue; long d1=0,d2=0;
        for(int y=0;y<T;y++)for(int x=0;x<T;x++){int k=(ty*T+y)*w+tx*T+x; d1+=Math.abs(la[k]-lb[k]); d2+=Math.abs(lb[k]-lc[k]);}
        double m1=d1/(double)(T*T),m2=d2/(double)(T*T); double pop=Math.abs(m2-m1)-0.5*Math.min(m1,m2);
        if(pop>12){pops++; if(pop>worst){worst=pop;wx=tx*T;wy=ty*T;}} }
      total+=pops; out.add(String.format("%s pops %d worst %.0f at %d,%d",fs[i].getName(),pops,worst,wx,wy));
    }
    System.out.println("TOTAL "+total); out.stream().filter(s->!s.contains("pops 0 ")).forEach(System.out::println);
  }
}
