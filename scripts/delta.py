"""ZIP-aware binary copy/add patch generator; no client dependency required."""
import gzip, hashlib, io, struct, zipfile
from pathlib import Path

def spans(data):
    """Separate local headers, compressed payloads and the central directory."""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            cursor=0
            for item in sorted(archive.infolist(),key=lambda x:x.header_offset):
                start=item.header_offset
                name_len,extra_len=struct.unpack_from('<HH',data,start+26)
                payload=start+30+name_len+extra_len
                if payload>cursor:yield cursor,data[cursor:payload]
                end=payload+item.compress_size
                if end>len(data):raise ValueError('ZIP payload outside file')
                if end>payload:yield payload,data[payload:end]
                cursor=end
            if cursor<len(data):yield cursor,data[cursor:]
    except zipfile.BadZipFile:
        yield 0,data

def make_patch(base:Path,target:Path,output:Path):
    old=base.read_bytes();new=target.read_bytes()
    lookup={hashlib.sha256(chunk).digest():(offset,len(chunk)) for offset,chunk in spans(old)}
    stream=io.BytesIO();stream.write(b'AERODLT1'+struct.pack('>q',len(new)))
    for _,chunk in spans(new):
        match=lookup.get(hashlib.sha256(chunk).digest())
        if match and old[match[0]:match[0]+match[1]]==chunk:
            stream.write(b'\x01'+struct.pack('>qq',*match))
        else:stream.write(b'\x02'+struct.pack('>q',len(chunk))+chunk)
    stream.write(b'\x00');output.write_bytes(gzip.compress(stream.getvalue(),compresslevel=9,mtime=0))
