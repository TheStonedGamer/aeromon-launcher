package cc.aeromon.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

/** Bounded copy/add patches. The caller verifies base, patch and output hashes. */
final class DeltaPatch {
    static void apply(Path base,Path patch,Path output,long expectedSize)throws IOException {
        try(var input=new DataInputStream(new GZIPInputStream(Files.newInputStream(patch)));
            var source=new RandomAccessFile(base.toFile(),"r");
            var out=new BufferedOutputStream(Files.newOutputStream(output))) {
            if(input.readLong()!=0x4145524f444c5431L || input.readLong()!=expectedSize)throw new IOException("Invalid delta header");
            byte[] buffer=new byte[65536];long written=0;
            while(true){
                int opcode=input.readUnsignedByte();
                if(opcode==0){if(written!=expectedSize || input.read()!=-1)throw new IOException("Invalid delta end");break;}
                if(opcode!=1 && opcode!=2)throw new IOException("Invalid delta operation");
                long offset=opcode==1?input.readLong():0,length=input.readLong();
                if(length<=0 || length>expectedSize-written)throw new IOException("Delta output exceeds signed size");
                if(opcode==1){if(offset<0 || offset>source.length() || length>source.length()-offset)throw new IOException("Delta copy outside base");source.seek(offset);}
                long remaining=length;
                while(remaining>0){int n=(int)Math.min(buffer.length,remaining);if(opcode==1)source.readFully(buffer,0,n);else input.readFully(buffer,0,n);out.write(buffer,0,n);remaining-=n;}
                written+=length;
            }
        }
    }
}
