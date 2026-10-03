using System;
using System.IO.Pipes;
using System.Reflection;
using System.Text;
using System.Threading.Tasks;
namespace SoloTrainer {
    internal static class AdapterConnectionTest {
        public static int Main() {
            string name="SoloTrainer-reuse-test-"+Guid.NewGuid().ToString("N");
            int pings=0, resets=0;
            using(var server=new NamedPipeServerStream(name,PipeDirection.InOut,1,PipeTransmissionMode.Byte,PipeOptions.Asynchronous)) {
                var worker=Task.Run(delegate {
                    server.WaitForConnection();
                    byte[] data=new byte[256]; int read;
                    while((read=server.Read(data,0,data.Length))>0) {
                        string command=Encoding.ASCII.GetString(data,0,read);
                        if(command=="PING") pings++; else if(command=="OFF") resets++; else throw new Exception(command);
                        byte[] reply=Encoding.ASCII.GetBytes(command=="PING"?"OK FLY=1 RESET=0 MRESET=0":"OK OFF");
                        server.Write(reply,0,reply.Length); server.Flush();
                    }
                });
                var client=new NamedPipeClientStream(".",name,PipeDirection.InOut,PipeOptions.Asynchronous);
                client.Connect(1000);
                var constructor=typeof(ClientAdapter).GetConstructor(BindingFlags.Instance|BindingFlags.NonPublic,null,new[]{typeof(NamedPipeClientStream),typeof(int)},null);
                var adapter=(ClientAdapter)constructor.Invoke(new object[]{client,123});
                for(int i=0;i<20;i++) if(!Object.ReferenceEquals(adapter,ClientAdapter.Reuse(adapter))) throw new Exception("Live connection replaced");
                if(pings!=20||resets!=0) throw new Exception("Reattach reset native controls");
                adapter.Dispose();
                if(!worker.Wait(3000)||resets!=1) throw new Exception("Connection not closed with reset");
            }
            Console.WriteLine("PASS: 20 repeated attaches reuse one pipe; no reset until disposal.");
            return 0;
        }
    }
}
