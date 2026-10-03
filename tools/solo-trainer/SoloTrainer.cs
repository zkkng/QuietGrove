using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.IO.Pipes;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Runtime.InteropServices;

namespace SoloTrainer {
    internal static class Wire {
        public static string Encode(Dictionary<string,string> fields) {
            var parts = new List<string>();
            foreach(var p in fields) parts.Add(Uri.EscapeDataString(p.Key)+"="+Uri.EscapeDataString(p.Value));
            return String.Join("&", parts.ToArray());
        }
        public static Dictionary<string,string> Decode(string input) {
            if(input.Length>8192) throw new InvalidDataException("Server response too large");
            var result=new Dictionary<string,string>(); if(input.Length==0) return result;
            foreach(string item in input.Split('&')) {
                int at=item.IndexOf('='); if(at<1) throw new InvalidDataException("Invalid server response");
                string key=Uri.UnescapeDataString(item.Substring(0,at).Replace("+"," "));
                string value=Uri.UnescapeDataString(item.Substring(at+1).Replace("+"," "));
                if(result.ContainsKey(key)||key.Length>32||value.Length>2048) throw new InvalidDataException("Invalid duplicate/oversized response");
                result.Add(key,value);
            }
            return result;
        }
        public static string Hash(byte[] bytes) { using(var sha=SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-",""); }
        public static bool ValidPin(string pin) {
            if(pin.Length!=64) return false;
            foreach(char c in pin) if(!Uri.IsHexDigit(c)) return false;
            return true;
        }
    }
    internal sealed class Bridge {
        private const string Endpoint="http://192.168.1.103:8488/v3/";
        public string Token;
        public Dictionary<string,string> Send(string operation, Dictionary<string,string> fields) {
            var request=(HttpWebRequest)WebRequest.Create(Endpoint+operation);
            request.Method="POST"; request.ContentType="application/x-www-form-urlencoded"; request.Timeout=3500; request.ReadWriteTimeout=3500;
            request.AllowAutoRedirect=false; request.Proxy=null; request.KeepAlive=false;
            if(Token!=null) request.Headers["Authorization"]="Bearer "+Token;
            byte[] body=Encoding.UTF8.GetBytes(Wire.Encode(fields)); request.ContentLength=body.Length;
            using(var output=request.GetRequestStream()) output.Write(body,0,body.Length);
            WebResponse response;
            try { response=request.GetResponse(); }
            catch(WebException error) { if(error.Response==null) throw new IOException("Bridge "+operation+" failed: "+error.Status+". "+error.Message,error); response=error.Response; }
            using(response) using(var input=response.GetResponseStream()) using(var memory=new MemoryStream()) {
                byte[] buffer=new byte[1024]; int read;
                while((read=input.Read(buffer,0,buffer.Length))>0) { if(memory.Length+read>8192) throw new InvalidDataException("Oversized response"); memory.Write(buffer,0,read); }
                var result=Wire.Decode(Encoding.UTF8.GetString(memory.ToArray()));
                if(result.ContainsKey("error")) throw new IOException(result["error"]);
                if(!result.ContainsKey("protocol")||result["protocol"]!="SoloMapling-Trainer-v3") throw new IOException("Candidate server patch is not installed yet.");
                return result;
            }
        }
    }
    internal sealed class ClientAdapter : IDisposable {
        private NamedPipeClientStream pipe;
        private readonly object sendLock=new object();
        public static string LastError;
        public bool FlyReady, FlyConfigReady, UnlimitedReady, RapidReady, SkillEffectsReady, FallThroughReady, HoverReady, CpuReady, MovementReset;
        private string movementGeneration;
        public int GameProcessId;
        private string resetGeneration;
        private ClientAdapter(NamedPipeClientStream stream,int gameProcessId) { pipe=stream; GameProcessId=gameProcessId; }
        public static ClientAdapter Reuse(ClientAdapter existing) {
            if(existing==null) return null;
            try { if(existing.Send("PING").StartsWith("OK ")) return existing; }
            catch(Exception error) { LastError=error.Message; }
            existing.Dispose(); return null;
        }
        public static ClientAdapter TryConnect() {
            var liveGames=new List<Process>();
            foreach(var game in Process.GetProcessesByName("MapleStory")) {
                try { if(!game.HasExited) liveGames.Add(game); else game.Dispose(); }
                catch(InvalidOperationException) { game.Dispose(); }
            }
            var games=liveGames.ToArray();
            if(games.Length!=1) { LastError="Expected one MapleStory process; found "+games.Length; return null; }
            var stream=new NamedPipeClientStream(".","SoloTrainerClient-"+games[0].Id,PipeDirection.InOut,PipeOptions.Asynchronous);
            try {
                stream.Connect(300);
                var adapter=new ClientAdapter(stream,games[0].Id);
                string result=adapter.Send("PING");
                if(result.StartsWith("OK ")) {
                    adapter.FlyReady=result.Contains("FLY=1"); adapter.FlyConfigReady=result.Contains("FLYCFG=1");
                    adapter.UnlimitedReady=result.Contains("UNLIMITED=1");
                    adapter.RapidReady=result.Contains("RAPID=1");
                    adapter.SkillEffectsReady=result.Contains("SKILLFX=1");
                    adapter.FallThroughReady=result.Contains("FALLTHROUGH=1");
                    adapter.HoverReady=result.Contains("HOVER=1"); adapter.CpuReady=result.Contains("CPU=1");
                    LastError=null; return adapter;
                }
                LastError="Client hook reported "+result;
                adapter.Dispose(); return null;
            } catch(Exception error) { LastError=error.ToString(); stream.Dispose(); return null; }
        }
        public string Send(string command) {
            lock(sendLock) {
                if(pipe==null) throw new IOException("Client adapter disconnected.");
                byte[] bytes=Encoding.ASCII.GetBytes(command);
                var write=pipe.BeginWrite(bytes,0,bytes.Length,null,null);
                using(var completed=write.AsyncWaitHandle) {
                    if(!completed.WaitOne(1000)) { pipe.Dispose(); pipe=null; throw new IOException("Client adapter write timed out."); }
                    pipe.EndWrite(write);
                }
                byte[] response=new byte[256];
                var pending=pipe.BeginRead(response,0,response.Length,null,null);
                int read;
                using(var completed=pending.AsyncWaitHandle) {
                    if(!completed.WaitOne(1000)) { pipe.Dispose(); pipe=null; throw new IOException("Client adapter response timed out."); }
                    read=pipe.EndRead(pending);
                }
                if(read<=0) throw new IOException("Client adapter gave no response.");
                string result=Encoding.ASCII.GetString(response,0,read);
                if(command=="PING"&&result.StartsWith("OK ")) {
                    foreach(string field in result.Split(' ')) if(field.StartsWith("MRESET=")) {
                        if(movementGeneration!=null&&movementGeneration!=field) MovementReset=true;
                        movementGeneration=field;
                    }
                    foreach(string field in result.Split(' ')) if(field.StartsWith("RESET=")) {
                        if(resetGeneration!=null&&resetGeneration!=field) throw new IOException("Native lease expired; controls were reset.");
                        resetGeneration=field;
                    }
                }
                return result;
            }
        }
        public string CloseWithReset() {
            lock(sendLock) {
                if(pipe==null) return "ERR NO_PIPE";
                string result;
                try { result=Send("OFF"); }
                catch(Exception error) { result="ERR "+error.Message; }
                if(pipe!=null) { pipe.Dispose(); pipe=null; }
                return result;
            }
        }
        public void Dispose() { CloseWithReset(); }
    }
    internal sealed class FlyShortcut {
        private bool held;
        public bool Pressed(bool down,bool allowed) {
            bool pressed=down&&!held;
            held=down;
            return pressed&&allowed;
        }
        public void Reset() { held=false; }
    }
    internal static class PresetFile {
        // Version 1 compatibility. Version 2 composes all implemented controls; explicit Apply only.
        private static readonly string[] Flags={"vac","itemVac","mesoVac","lootOnKey","fma","fmaOneHit","hpGod","hpRegen","mpRegen"};
        private static readonly string[] Numbers={"lootRadius","lootBatch","minMeso","maxMeso","fmaDamage","interval"};
        private static readonly int[] Minimums={0,1,0,0,1,150}, Maximums={2000,25,Int32.MaxValue,Int32.MaxValue,100,1000};
        public static Dictionary<string,string> Validate(Dictionary<string,string> input) {
            var result=new Dictionary<string,string>();
            foreach(string key in Flags) { string value; if(!input.TryGetValue(key,out value)||(value!="0"&&value!="1")) throw new InvalidDataException("Invalid preset "+key); result.Add(key,value); }
            for(int i=0;i<Numbers.Length;i++) { string value; int number; if(!input.TryGetValue(Numbers[i],out value)||!Int32.TryParse(value,out number)||number<Minimums[i]||number>Maximums[i]) throw new InvalidDataException("Invalid preset "+Numbers[i]); result.Add(Numbers[i],number.ToString()); }
            if(Int32.Parse(result["minMeso"])>Int32.Parse(result["maxMeso"])) throw new InvalidDataException("Meso minimum exceeds maximum.");
            foreach(string key in new[]{"includeIds","excludeIds"}) {
                string value; if(!input.TryGetValue(key,out value)||value.Length>256) throw new InvalidDataException("Invalid preset item filter.");
                if(value.Length>0) foreach(string token in value.Split(',')) { int id; if(!Int32.TryParse(token.Trim(),out id)||id<1) throw new InvalidDataException("Invalid preset item ID."); }
                result.Add(key,value);
            }
            foreach(string key in new[]{"vacMode","lootOrder"}) {
                string value; if(!input.TryGetValue(key,out value)) throw new InvalidDataException("Missing preset "+key);
                if(key=="vacMode" ? (value!="front"&&value!="left wall"&&value!="right wall") : (value!="nearest"&&value!="newest"&&value!="highest value"&&value!="owner stake first")) throw new InvalidDataException("Invalid preset "+key);
                result.Add(key,value);
            }
            if(input.Count!=result.Count) throw new InvalidDataException("Unknown preset field or version.");
            return result;
        }
        public static string Encode(string character,Dictionary<string,string> fields) {
            var values=Validate(fields); values.Add("version","1"); values.Add("character",character);
            return Wire.Encode(values);
        }
        public static Dictionary<string,string> Decode(string encoded,string character) {
            var fields=Wire.Decode(encoded); string version,savedCharacter;
            if(!fields.TryGetValue("version",out version)||version!="1") throw new InvalidDataException("Unsupported preset version; no settings applied.");
            if(!fields.TryGetValue("character",out savedCharacter)||savedCharacter!=character) throw new InvalidDataException("This preset belongs to another character.");
            fields.Remove("version"); fields.Remove("character"); return Validate(fields);
        }
        public static string EncodeProfile(string character,Dictionary<string,string> sections) {
            var copy=new Dictionary<string,string>(sections); copy.Add("version","2"); copy.Add("character",character);
            string encoded=Wire.Encode(copy); DecodeProfile(encoded,character); return encoded;
        }
        public static Dictionary<string,string> DecodeProfile(string encoded,string character) {
            var fields=Wire.Decode(encoded);
            if(!fields.ContainsKey("version")||fields["version"]!="2"||!fields.ContainsKey("character")||fields["character"]!=character) throw new InvalidDataException("Unsupported profile version or different character.");
            fields.Remove("version"); fields.Remove("character");
            string[] keys={"core","potions","powers","loot","mobs","pointMap","native"};
            foreach(string key in fields.Keys) if(Array.IndexOf(keys,key)<0 && key!="pickup" && key!="regen") throw new InvalidDataException("Unknown profile section");
            if(!fields.ContainsKey("regen")) fields.Add("regen","hpPercent=10&mpPercent=10&interval=1000");
            if(!fields.ContainsKey("pickup")) fields.Add("pickup","tubi=0&burst=0&interval=150&batch=10&scenarioAge=0&minimumAge=400&minWatk=0&minMatk=0&minSlots=0");
            foreach(string key in keys) if(!fields.ContainsKey(key)) throw new InvalidDataException("Missing profile section: "+key);
            var core=Wire.Decode(fields["core"]); string rapid;
            if(!core.TryGetValue("rapid",out rapid)||(rapid!="0"&&rapid!="1")) throw new InvalidDataException("Invalid rapid flag");
            core.Remove("rapid"); Validate(core);
            int map; if(!Int32.TryParse(fields["pointMap"],out map)||map<0) throw new InvalidDataException("Invalid saved point map");
            var native=Wire.Decode(fields["native"]);
            string[] flags={"fly","hover","fallThrough","unlimited","skillEffects","cpu"};
            if(native.Count!=7&&native.Count!=11) throw new InvalidDataException("Unknown native profile field");
            if(native.Count==7) { native.Add("flySpeed","600"); native.Add("flyDeadZone","8"); native.Add("flyInertia","40"); native.Add("flyVertical","1"); }
            string[] flyOptions={"flySpeed","flyDeadZone","flyInertia","flyVertical"}; int[] mins={50,0,0,0},maxs={2000,100,95,1};
            for(int i=0;i<flyOptions.Length;i++) { int value; if(!native.ContainsKey(flyOptions[i])||!Int32.TryParse(native[flyOptions[i]],out value)||value<mins[i]||value>maxs[i]) throw new InvalidDataException("Invalid "+flyOptions[i]); }
            fields["native"]=Wire.Encode(native);
            foreach(string key in flags) if(!native.ContainsKey(key)||(native[key]!="0"&&native[key]!="1")) throw new InvalidDataException("Invalid native flag "+key);
            int fps; if(!native.ContainsKey("fps")||!Int32.TryParse(native["fps"],out fps)||fps<10||fps>60) throw new InvalidDataException("Background rate must be 10..60");
            if((native["fly"]=="1"?1:0)+(native["hover"]=="1"?1:0)+(native["fallThrough"]=="1"?1:0)>1) throw new InvalidDataException("Fly, Hover and Fall Through are mutually exclusive");
            return fields;
        }
        public static void Save(string path,string encoded) {
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            string temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
            try {
                using(var stream=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None)) {
                    byte[] bytes=Encoding.UTF8.GetBytes(encoded); stream.Write(bytes,0,bytes.Length); stream.Flush(true);
                }
                if(File.Exists(path)) File.Replace(temporary,path,null); else File.Move(temporary,path);
            } finally { if(File.Exists(temporary)) File.Delete(temporary); }
        }
        public static void SelfTest() {
            var fields=Wire.Decode("vac=1&itemVac=1&mesoVac=1&lootOnKey=0&fma=1&fmaOneHit=0&hpGod=0&hpRegen=0&mpRegen=0&lootRadius=0&lootBatch=8&minMeso=0&maxMeso=2147483647&fmaDamage=1&interval=300&includeIds=2040811%2C2049100&excludeIds=&vacMode=front&lootOrder=nearest");
            string encoded=Encode("TestCharacter",fields);
            if(Decode(encoded,"TestCharacter")["includeIds"]!="2040811,2049100") throw new Exception("Preset roundtrip failed");
            Action<Action> rejected=delegate(Action action) { try { action(); } catch(InvalidDataException) { return; } throw new Exception("Invalid preset accepted"); };
            rejected(delegate { Decode(encoded,"OtherCharacter"); });
            rejected(delegate { Decode(encoded.Replace("version=1","version=2"),"TestCharacter"); });
            rejected(delegate { Decode(encoded+"&token=secret","TestCharacter"); });
            rejected(delegate { Decode(encoded.Replace("lootBatch=8","lootBatch=26"),"TestCharacter"); });
            rejected(delegate { Decode(encoded.Replace("minMeso=0","minMeso=999").Replace("maxMeso=2147483647","maxMeso=1"),"TestCharacter"); });
            rejected(delegate { Decode(encoded.Replace("includeIds=2040811%2C2049100","includeIds=bad"),"TestCharacter"); });
            string testPath=Path.Combine(Path.GetTempPath(),"SoloTrainer-preset-test-"+Guid.NewGuid().ToString("N")+".preset");
            try {
                Save(testPath,encoded); fields["vac"]="0"; Save(testPath,Encode("TestCharacter",fields));
                if(Decode(File.ReadAllText(testPath),"TestCharacter")["vac"]!="0") throw new Exception("Atomic preset replacement failed");
            } finally { if(File.Exists(testPath)) File.Delete(testPath); }
        }
    }
    internal sealed class TrainerWindow : Form {
        private sealed class FeaturePanel : TabPage {
            private readonly Dictionary<string,Control> inputs=new Dictionary<string,Control>();
            private readonly TableLayoutPanel rows=new TableLayoutPanel();
            private readonly Label effective=new Label();
            private readonly Button apply=new Button();
            private readonly FlowLayoutPanel actions=new FlowLayoutPanel();
            private bool syncing,dirty,ready;
            public FeaturePanel(string title,string guidance,Func<Dictionary<string,string>,Task> submit) : base(title) {
                BackColor=Color.FromArgb(17,19,18); ForeColor=Color.GreenYellow;
                rows.Dock=DockStyle.Fill; rows.AutoScroll=true; rows.ColumnCount=2; rows.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,52)); rows.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,48));
                var help=new Label(); help.Text=guidance; help.AutoSize=true; help.MaximumSize=new Size(590,0); rows.Controls.Add(help,0,0); rows.SetColumnSpan(help,2); rows.RowCount=1;
                effective.Dock=DockStyle.Bottom; effective.Height=30; effective.ForeColor=Color.Cyan; effective.Text="Requires matching server capability. Default OFF.";
                actions.Dock=DockStyle.Bottom; actions.Height=34;
                apply.Text="APPLY SETTINGS"; apply.Width=165; apply.Height=29; apply.FlatStyle=FlatStyle.Flat; apply.BackColor=Color.DarkOliveGreen; apply.ForeColor=Color.White;
                apply.Click+=async delegate { var fields=new Dictionary<string,string>(); foreach(var pair in inputs) { var check=pair.Value as CheckBox; var number=pair.Value as NumericUpDown; fields.Add(pair.Key,check!=null?(check.Checked?"1":"0"):number!=null?number.Value.ToString():pair.Value.Text); } await submit(fields); };
                actions.Controls.Add(apply); Controls.Add(rows); Controls.Add(effective); Controls.Add(actions);
            }
            public Dictionary<string,string> CaptureFields() {
                var fields=new Dictionary<string,string>();
                foreach(var pair in inputs) { var check=pair.Value as CheckBox; var number=pair.Value as NumericUpDown;
                    fields.Add(pair.Key,check!=null?(check.Checked?"1":"0"):number!=null?number.Value.ToString():pair.Value.Text); }
                return fields;
            }
            public void ValidateFields(Dictionary<string,string> fields) {
                if(fields.Count!=inputs.Count) throw new InvalidDataException("Incomplete or unknown "+Text+" profile");
                foreach(var pair in inputs) {
                    string value; if(!fields.TryGetValue(pair.Key,out value)) throw new InvalidDataException("Missing "+pair.Key);
                    if(pair.Value is CheckBox) { if(value!="0"&&value!="1") throw new InvalidDataException("Invalid "+pair.Key); }
                    else if(pair.Value is NumericUpDown) { decimal number; var control=(NumericUpDown)pair.Value; if(!Decimal.TryParse(value,out number)||number!=Decimal.Truncate(number)||number<control.Minimum||number>control.Maximum) throw new InvalidDataException("Invalid "+pair.Key); }
                    else if(pair.Value is ComboBox) { if(!((ComboBox)pair.Value).Items.Contains(value)) throw new InvalidDataException("Invalid "+pair.Key); }
                    else if(value.Length>256) throw new InvalidDataException("Oversized "+pair.Key);
                }
            }
            private void Add(string key,string label,Control input) {
                int row=rows.RowCount++; rows.RowStyles.Add(new RowStyle(SizeType.AutoSize));
                var caption=new Label(); caption.Text=label; caption.AutoSize=true; caption.Padding=new Padding(8,5,0,0);
                input.Dock=DockStyle.Top; input.Margin=new Padding(4,3,15,3); input.BackColor=Color.Black; input.ForeColor=Color.GreenYellow;
                rows.Controls.Add(caption,0,row); rows.Controls.Add(input,1,row); inputs.Add(key,input);
                input.TextChanged+=delegate { if(!syncing) dirty=true; };
                var checkbox=input as CheckBox; if(checkbox!=null) checkbox.CheckedChanged+=delegate { if(!syncing) dirty=true; };
                var numeric=input as NumericUpDown; if(numeric!=null) numeric.ValueChanged+=delegate { if(!syncing) dirty=true; };
            }
            public void Flag(string key,string label) { Add(key,label,new CheckBox()); }
            public void Number(string key,string label,int min,int max,int initial) { Add(key,label,new NumericUpDown{Minimum=min,Maximum=max,Value=initial}); }
            public void TextField(string key,string label) { Add(key,label,new TextBox{MaxLength=256}); }
            public void Choice(string key,string label,string[] choices) { var box=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList}; box.Items.AddRange(choices); box.SelectedIndex=0; Add(key,label,box); }
            public void ActionButton(string label,Func<Task> action) { var button=new Button{Text=label,Width=130,Height=29,FlatStyle=FlatStyle.Flat,BackColor=Color.DarkOliveGreen,ForeColor=Color.White}; button.Click+=async delegate { await action(); }; actions.Controls.Add(button); }
            public void EnableEditing(bool enabled) {
                bool editable=enabled&&ready;
                // Keep captions readable even before attachment; gate only the editors.
                foreach(var input in inputs.Values) input.Enabled=editable;
                foreach(Control action in actions.Controls) action.Enabled=editable;
            }
            public void Accepted() { dirty=false; }
            public void Reset() { dirty=false; ready=false; syncing=true; foreach(var input in inputs.Values) { var checkbox=input as CheckBox; if(checkbox!=null) checkbox.Checked=false; } syncing=false; effective.Text="OFF / awaiting server capability"; EnableEditing(false); }
            public void State(Dictionary<string,string> state,string capability,Dictionary<string,string> aliases) {
                ready=state.ContainsKey(capability)&&state[capability]=="1"; if(!ready) return;
                var enabled=new List<string>(); syncing=true;
                try { foreach(var pair in inputs) {
                    string key=aliases.ContainsKey(pair.Key)?aliases[pair.Key]:pair.Key;
                    if(!state.ContainsKey(key)) throw new IOException("Missing feature state: "+key);
                    string value=state[key]; var checkbox=pair.Value as CheckBox;
                    if(checkbox!=null) { if(value!="0"&&value!="1") throw new IOException("Invalid feature flag"); if(value=="1") enabled.Add(pair.Key); }
                    if(dirty) continue;
                    if(checkbox!=null) checkbox.Checked=value=="1";
                    else if(pair.Value is NumericUpDown) ((NumericUpDown)pair.Value).Value=Decimal.Parse(value);
                    else if(pair.Value is ComboBox) { var box=(ComboBox)pair.Value; if(!box.Items.Contains(value)) throw new IOException("Unsupported feature value"); box.SelectedItem=value; }
                    else pair.Value.Text=value;
                }} finally { syncing=false; }
                effective.Text="Server: "+(enabled.Count==0?"switches OFF":String.Join(", ",enabled.ToArray()))+(dirty?" / unapplied edits":" / settings confirmed");
            }
        }
        private FeaturePanel powerPanel,lootToolsPanel,mobToolsPanel,pickupPanel,regenPanel;
        private readonly ComboBox inspectorKind=new ComboBox();
        private readonly DataGridView inspectorRows=new DataGridView();
        private readonly Panel inspectorCanvas=new Panel();
        private readonly Label inspectorStatus=new Label();
        private Dictionary<string,string> inspection;
        private int inspectorOffset;
        private readonly TextBox profilePreview=new TextBox(), observationLog=new TextBox();
        private readonly Label sessionStats=new Label();
        private readonly CheckBox showObservations=new CheckBox();
        private bool profileReady, profileApplying;
        private string currentMap="0";
        private readonly Color neon=Color.FromArgb(172,255,27);
        private readonly TextBox log=new TextBox();
        private readonly CheckBox vac=new CheckBox(), itemVac=new CheckBox(), mesoVac=new CheckBox(), lootOnKey=new CheckBox(), fma=new CheckBox(), fmaOneHit=new CheckBox(), hpGod=new CheckBox(), hpRegen=new CheckBox(), mpRegen=new CheckBox(), fly=new CheckBox(), unlimited=new CheckBox(), rapid=new CheckBox(), skillEffects=new CheckBox(), fallThrough=new CheckBox();
        private readonly NumericUpDown fmaDamage=new NumericUpDown(), lootRadius=new NumericUpDown(), lootBatch=new NumericUpDown(), attackInterval=new NumericUpDown();
        private readonly NumericUpDown minMeso=new NumericUpDown(), maxMeso=new NumericUpDown();
        private readonly TextBox includeIds=new TextBox(), excludeIds=new TextBox();
        private readonly ComboBox lootOrder=new ComboBox();
        private readonly ComboBox vacMode=new ComboBox();
        private readonly ComboBox flyKeyChoice=new ComboBox();
        private readonly ComboBox presetChoice=new ComboBox();
        private readonly Button savePreset=new Button(), loadPreset=new Button();
        private readonly CheckBox autoHp=new CheckBox(), autoMp=new CheckBox(), hover=new CheckBox(), cpuMode=new CheckBox();
        private readonly NumericUpDown backgroundFps=new NumericUpDown(),flySpeed=new NumericUpDown(),flyDeadZone=new NumericUpDown(),flyInertia=new NumericUpDown();
        private readonly CheckBox flyVertical=new CheckBox();
        private readonly Button applyFlyOptions=new Button();
        private bool extraNativeBusy;
        private string lastNativeMap;
        private readonly NumericUpDown autoHpThreshold=new NumericUpDown(), autoMpThreshold=new NumericUpDown(), autoReserve=new NumericUpDown(), autoInterval=new NumericUpDown();
        private readonly ComboBox autoHpItem=new ComboBox(), autoMpItem=new ComboBox();
        private readonly Button applyAutoPotion=new Button();
        private readonly Label autoPotionStatus=new Label();
        private bool autoPotionReady, autoPotionDraft;
        private string characterName;
        private readonly Label status=new Label(), counters=new Label(), identity=new Label();
        private readonly Button connect=new Button(), apply=new Button(), panic=new Button(), lootNow=new Button();
        private readonly Timer timer=new Timer();
        private readonly Timer shortcutTimer=new Timer();
        private readonly FlyShortcut flyShortcut=new FlyShortcut();
        private readonly int ownProcessId=Process.GetCurrentProcess().Id;
        private readonly string flyKeyPath=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"SoloTrainer.fly-key.txt");
        private Keys flyKey=Keys.F6;
        private readonly string logPath=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"SoloTrainer.log");
        private Bridge bridge;
        private ClientAdapter adapter;
        private bool busy, paired, closing, panicBusy, syncing, pendingConfiguration, filtersInitialized, flyBusy, unlimitedBusy, rapidBusy, skillEffectsBusy, fallThroughBusy;
        private long lastLootPulse;
        private long lastLoggedFmaCast;
        private int epoch;
        [DllImport("user32.dll")] private static extern bool RegisterHotKey(IntPtr hWnd,int id,uint modifiers,uint key);
        [DllImport("user32.dll")] private static extern bool UnregisterHotKey(IntPtr hWnd,int id);
        [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int key);
        [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window,out uint processId);
        public TrainerWindow() {
            Text="[xX_SoloH4x_Xx] SoloTrainer v0.6 - OWN SERVER TEST"; ClientSize=new Size(680,570);
            StartPosition=FormStartPosition.CenterScreen; FormBorderStyle=FormBorderStyle.FixedSingle; MaximizeBox=false;
            BackColor=Color.FromArgb(17,19,18); ForeColor=neon; Font=new Font("Consolas",9f); AutoScaleMode=AutoScaleMode.Dpi;
            Icon=SystemIcons.Warning;
            var header=LabelAt("SOLO H4X // v83",14,10,650,42); header.Font=new Font("Impact",29f,FontStyle.Italic); header.ForeColor=Color.Lime;
            LabelAt("FREE PRIVATE RELEASE 2007  //  NO PAYWALL. NO INJECTOR. OWN SERVER ONLY.",16,57,652,22).ForeColor=Color.Cyan;
            var ad=LabelAt("[ FAKE SCENE AD ]  DOWNLOAD MORE RAM.exe  >>>  cosmetic only!",16,82,648,30);
            ad.BackColor=Color.FromArgb(75,12,54); ad.ForeColor=Color.HotPink; ad.TextAlign=ContentAlignment.MiddleCenter;
            identity.SetBounds(16,121,648,34); identity.Text="GAME: not attached  |  SERVER: OFFLINE  |  ADAPTER: unchanged"; Controls.Add(identity);
            var tabs=new TabControl(); tabs.SetBounds(16,159,648,235); tabs.Font=Font;
            var main=new TabPage("[ MAIN HACKS ]"); main.BackColor=BackColor; main.ForeColor=neon; tabs.TabPages.Add(main);
            var combat=new TabPage("[ COMBAT ]"); combat.BackColor=BackColor; combat.ForeColor=neon; tabs.TabPages.Add(combat);
            var mobs=new TabPage("[ MOBS ]"); mobs.BackColor=BackColor; mobs.ForeColor=neon; tabs.TabPages.Add(mobs);
            var movement=new TabPage("[ MOVEMENT ]"); movement.BackColor=BackColor; movement.ForeColor=neon; tabs.TabPages.Add(movement);
            Check(movement,fly,"MOUSE FLY   // F6 toggles ON / OFF",12,14);
            var flyKeyLabel=new Label(); flyKeyLabel.Text="Fly shortcut:"; flyKeyLabel.SetBounds(18,59,130,24); movement.Controls.Add(flyKeyLabel);
            flyKeyChoice.SetBounds(148,56,100,26); flyKeyChoice.DropDownStyle=ComboBoxStyle.DropDownList;
            flyKeyChoice.Items.AddRange(new object[]{"F6","F7","F8","F9","F10"}); flyKeyChoice.BackColor=Color.Black; flyKeyChoice.ForeColor=neon; movement.Controls.Add(flyKeyChoice);
            string savedKey="F6"; try { if(File.Exists(flyKeyPath)) savedKey=File.ReadAllText(flyKeyPath).Trim(); } catch {}
            if(!flyKeyChoice.Items.Contains(savedKey)) savedKey="F6";
            flyKeyChoice.SelectedItem=savedKey; flyKey=(Keys)Enum.Parse(typeof(Keys),savedKey);
            fly.Text="MOUSE FLY   // "+savedKey+" toggles ON / OFF";
            flyKeyChoice.SelectedIndexChanged+=delegate {
                flyKey=(Keys)Enum.Parse(typeof(Keys),flyKeyChoice.Text); flyShortcut.Reset();
                fly.Text="MOUSE FLY   // "+flyKeyChoice.Text+" toggles ON / OFF";
                try { File.WriteAllText(flyKeyPath,flyKeyChoice.Text); } catch(Exception error) { Append("Could not save fly shortcut: "+error.Message); }
            };
            var flyNote=new Label(); flyNote.SetBounds(18,99,590,74); flyNote.ForeColor=Color.Cyan;
            flyNote.Text="Press the shortcut while the game or trainer is focused.\r\nNew adapter: hold ALT inside playfield to steer; release to land.\r\nThe switch and shortcut control the same Fly setting.\r\nALL OFF clears flight too.";
            movement.Controls.Add(flyNote); fly.CheckedChanged+=async delegate { await SetFly(); };
            Check(movement,fallThrough,"FALL THROUGH   // candidate: use Down + Jump",12,170);
            fallThrough.CheckedChanged+=async delegate { await SetFallThrough(); };
            movement.AutoScroll=true;
            Check(movement,hover,"HOVER ALTITUDE   // arrows move; turn OFF to descend",12,205);
            hover.CheckedChanged+=async delegate { await SetExtraNative(false); };
            var flightSettings=new Label(); flightSettings.Text="Fly speed (units/s):          Dead zone:                 Inertia %:"; flightSettings.SetBounds(18,248,590,25); movement.Controls.Add(flightSettings);
            PotionNumber(movement,flySpeed,18,276,50,2000,600); PotionNumber(movement,flyDeadZone,224,276,0,100,8); PotionNumber(movement,flyInertia,430,276,0,95,40);
            Check(movement,flyVertical,"Allow vertical cursor steering",12,315); flyVertical.Width=370; flyVertical.Checked=true;
            applyFlyOptions.Text="APPLY FLY OPTIONS"; Style(applyFlyOptions); applyFlyOptions.SetBounds(393,315,210,30); movement.Controls.Add(applyFlyOptions); applyFlyOptions.Click+=async delegate { await ApplyFlyOptions(); };
            var vacModeLabel=new Label(); vacModeLabel.Text="Mob Vac anchor:"; vacModeLabel.SetBounds(18,20,165,24); mobs.Controls.Add(vacModeLabel);
            vacMode.SetBounds(178,17,170,26); vacMode.DropDownStyle=ComboBoxStyle.DropDownList; vacMode.Items.AddRange(new object[]{"front","left wall","right wall"}); vacMode.SelectedIndex=0; vacMode.BackColor=Color.Black; vacMode.ForeColor=neon; mobs.Controls.Add(vacMode);
            var mobsNote=new Label(); mobsNote.Text="Front groups mobs ahead of your facing direction.\r\nWall modes use the map's left or right edge and a valid foothold.\r\nUse the MOB VAC switch on Main to turn the effect on or off."; mobsNote.SetBounds(18,70,590,90); mobsNote.ForeColor=Color.Cyan; mobs.Controls.Add(mobsNote);
            var loot=new TabPage("[ LOOT ]"); loot.BackColor=BackColor; loot.ForeColor=neon; tabs.TabPages.Add(loot);
            Check(loot,itemVac,"[04] ITEM VAC   // real items",12,8);
            Check(loot,mesoVac,"[05] MESO VAC   // real mesos",315,8);
            itemVac.Width=300; mesoVac.Width=300;
            Check(loot,lootOnKey,"LOOT KEY MODE   // off = auto full map sweep",12,40);
            lootOnKey.Width=600;
            var radiusText=new Label(); radiusText.Text="Radius (0=whole map):"; radiusText.SetBounds(18,79,210,23); loot.Controls.Add(radiusText);
            lootRadius.SetBounds(220,76,74,24); lootRadius.Maximum=2000; lootRadius.Increment=100; lootRadius.BackColor=Color.Black; lootRadius.ForeColor=neon; loot.Controls.Add(lootRadius);
            var batchText=new Label(); batchText.Text="Per sweep:"; batchText.SetBounds(316,79,95,23); loot.Controls.Add(batchText);
            lootBatch.SetBounds(410,76,65,24); lootBatch.Minimum=1; lootBatch.Maximum=25; lootBatch.Value=8; lootBatch.BackColor=Color.Black; lootBatch.ForeColor=neon; loot.Controls.Add(lootBatch);
            lootNow.Text="SUCC NOW"; Style(lootNow); lootNow.SetBounds(490,74,130,28); loot.Controls.Add(lootNow); lootNow.Click+=async delegate { await PulseLoot(); };
            var lootNote=new Label(); lootNote.SetBounds(18,116,605,80); lootNote.Text="Auto: sweep every 500 ms. Key mode: game loot packet or SUCC NOW / Ctrl+F11.\r\nActual items and mesos, normal age / ownership / bag checks.\r\nIf the game sends no loot packet with empty space, use SUCC NOW."; loot.Controls.Add(lootNote);
            var filters=new TabPage("[ FILTERS ]"); filters.BackColor=BackColor; filters.ForeColor=neon; tabs.TabPages.Add(filters);
            var includeLabel=new Label(); includeLabel.Text="Only item IDs (blank=all):"; includeLabel.SetBounds(15,12,225,23); filters.Controls.Add(includeLabel);
            includeIds.SetBounds(235,9,378,25); includeIds.MaxLength=256; includeIds.BackColor=Color.Black; includeIds.ForeColor=neon; filters.Controls.Add(includeIds);
            var excludeLabel=new Label(); excludeLabel.Text="Exclude item IDs:"; excludeLabel.SetBounds(15,45,225,23); filters.Controls.Add(excludeLabel);
            excludeIds.SetBounds(235,42,378,25); excludeIds.MaxLength=256; excludeIds.BackColor=Color.Black; excludeIds.ForeColor=neon; filters.Controls.Add(excludeIds);
            var mesoLabel=new Label(); mesoLabel.Text="Meso bag range:"; mesoLabel.SetBounds(15,78,165,23); filters.Controls.Add(mesoLabel);
            minMeso.SetBounds(180,75,110,24); minMeso.Maximum=Int32.MaxValue; minMeso.BackColor=Color.Black; minMeso.ForeColor=neon; filters.Controls.Add(minMeso);
            maxMeso.SetBounds(310,75,110,24); maxMeso.Maximum=Int32.MaxValue; maxMeso.Value=Int32.MaxValue; maxMeso.BackColor=Color.Black; maxMeso.ForeColor=neon; filters.Controls.Add(maxMeso);
            var orderLabel=new Label(); orderLabel.Text="Order:"; orderLabel.SetBounds(15,111,90,23); filters.Controls.Add(orderLabel);
            lootOrder.SetBounds(105,108,160,25); lootOrder.DropDownStyle=ComboBoxStyle.DropDownList; lootOrder.Items.AddRange(new object[]{"nearest","newest","highest value","owner stake first"}); lootOrder.SelectedIndex=0; lootOrder.BackColor=Color.Black; lootOrder.ForeColor=neon; filters.Controls.Add(lootOrder);
            var filterApply=new Button(); filterApply.Text="APPLY FILTERS"; Style(filterApply); filterApply.SetBounds(385,107,228,29); filters.Controls.Add(filterApply); filterApply.Click+=async delegate { await Configure(); };
            var filterNote=new Label(); filterNote.Text="Comma-separated item IDs. Exclude wins over include.\r\nFilters affect real eligible drops only; no item creation."; filterNote.SetBounds(18,148,590,48); filters.Controls.Add(filterNote);
            var survival=new TabPage("[ SURVIVAL ]"); survival.BackColor=BackColor; survival.ForeColor=neon; tabs.TabPages.Add(survival);
            Check(survival,hpGod,"HP GOD   // incoming HP damage resolves to zero",12,12);
            Check(survival,hpRegen,"HP REGEN   // restore at configured HP rate",12,46);
            Check(survival,mpRegen,"MP REGEN   // restore at configured MP rate",12,80);
            var survivalNote=new Label(); survivalNote.Text="Server resource changes use normal stat updates.\r\nClient knockback and local hit animation may still occur.\r\nEvery switch can be turned off immediately."; survivalNote.SetBounds(18,126,600,65); survival.Controls.Add(survivalNote);
            var visuals=new TabPage("[ VISUALS ]"); visuals.BackColor=BackColor; visuals.ForeColor=neon; tabs.TabPages.Add(visuals);
            Check(visuals,skillEffects,"HIDE SKILL EFFECTS   // local display only",12,14);
            skillEffects.CheckedChanged+=async delegate { await SetSkillEffects(); };
            var visualNote=new Label(); visualNote.Text="New skill effects are hidden locally. OFF restores normal rendering.\r\nCPU mode caps the background game loop; foreground runs normally."; visualNote.SetBounds(18,53,590,48); visuals.Controls.Add(visualNote);
            Check(visuals,cpuMode,"CPU MODE   // background loop rate",12,112); cpuMode.Width=435;
            PotionNumber(visuals,backgroundFps,470,114,10,60,15);
            cpuMode.CheckedChanged+=async delegate { await SetExtraNative(true); };
            backgroundFps.ValueChanged+=async delegate { if(cpuMode.Checked) await SetExtraNative(true); };
            var presets=new TabPage("[ PRESETS ]"); presets.BackColor=BackColor; presets.ForeColor=neon; tabs.TabPages.Add(presets);
            presetChoice.SetBounds(18,16,220,26); presetChoice.DropDownStyle=ComboBoxStyle.DropDownList; presetChoice.Items.AddRange(new object[]{"Training","Looting","Custom"}); presetChoice.SelectedIndex=0; presets.Controls.Add(presetChoice);
            savePreset.Text="SAVE CURRENT"; Style(savePreset); savePreset.SetBounds(252,14,172,30); presets.Controls.Add(savePreset); savePreset.Click+=delegate { SavePreset(); };
            loadPreset.Text="APPLY SAVED"; Style(loadPreset); loadPreset.SetBounds(438,14,172,30); presets.Controls.Add(loadPreset); loadPreset.Click+=async delegate { await ApplyPreset(); };
            var previewPreset=new Button(); previewPreset.Text="PREVIEW"; Style(previewPreset); previewPreset.SetBounds(18,54,130,28); presets.Controls.Add(previewPreset); previewPreset.Click+=async delegate { await ApplyPreset(true); };
            var presetNote=new Label(); presetNote.Text="All implemented settings, including native switches. Explicit Apply only."; presetNote.SetBounds(155,58,450,24); presets.Controls.Add(presetNote);
            profilePreview.SetBounds(18,89,590,118); Input(profilePreview); profilePreview.Multiline=true; profilePreview.ReadOnly=true; profilePreview.ScrollBars=ScrollBars.Vertical; profilePreview.Text="Saved settings never activate automatically. PREVIEW validates without applying."; presets.Controls.Add(profilePreview);
            var automation=new TabPage("[ AUTO POT ]"); automation.BackColor=BackColor; automation.ForeColor=neon; tabs.TabPages.Add(automation);
            Check(automation,autoHp,"HP <=",12,6); autoHp.Width=94;
            Check(automation,autoMp,"MP <=",12,42); autoMp.Width=94;
            PotionNumber(automation,autoHpThreshold,108,8,1,99,50); PotionNumber(automation,autoMpThreshold,108,44,1,99,30);
            autoHpItem.SetBounds(218,8,392,25); autoHpItem.DropDownStyle=ComboBoxStyle.DropDownList; autoHpItem.Items.AddRange(new object[]{"2000000 Red Potion","2000001 Orange Potion","2000002 White Potion","2000004 Elixir","2000005 Power Elixir"}); autoHpItem.SelectedIndex=2; automation.Controls.Add(autoHpItem);
            autoMpItem.SetBounds(218,44,392,25); autoMpItem.DropDownStyle=ComboBoxStyle.DropDownList; autoMpItem.Items.AddRange(new object[]{"2000003 Blue Potion","2000006 Mana Elixir","2000004 Elixir","2000005 Power Elixir"}); autoMpItem.SelectedIndex=1; automation.Controls.Add(autoMpItem);
            var potionOptions=new Label(); potionOptions.Text="Reserve units:               Interval ms:"; potionOptions.SetBounds(18,84,592,25); automation.Controls.Add(potionOptions);
            PotionNumber(automation,autoReserve,145,80,0,1000,5); PotionNumber(automation,autoInterval,400,80,500,10000,1000);
            applyAutoPotion.Text="APPLY AUTO POT"; Style(applyAutoPotion); applyAutoPotion.SetBounds(398,120,212,30); automation.Controls.Add(applyAutoPotion); applyAutoPotion.Click+=async delegate { await ApplyAutoPotion(); };
            var potionNote=new Label(); potionNote.Text="Uses real potions. HP has priority.\r\nChanges take effect when you click APPLY."; potionNote.SetBounds(18,120,370,44); automation.Controls.Add(potionNote);
            autoPotionStatus.Text="Requires server auto-potion capability. Default OFF."; autoPotionStatus.SetBounds(18,174,595,36); autoPotionStatus.ForeColor=Color.Cyan; automation.Controls.Add(autoPotionStatus);
            foreach(var checkbox in new[]{autoHp,autoMp}) checkbox.CheckedChanged+=delegate { if(!syncing) autoPotionDraft=true; };
            foreach(var number in new[]{autoHpThreshold,autoMpThreshold,autoReserve,autoInterval}) number.ValueChanged+=delegate { if(!syncing) autoPotionDraft=true; };
            autoHpItem.SelectedIndexChanged+=delegate { if(!syncing) autoPotionDraft=true; }; autoMpItem.SelectedIndexChanged+=delegate { if(!syncing) autoPotionDraft=true; };
            powerPanel=new FeaturePanel("[ POWERS ]","Server overlays. Native client/skill restrictions may still block a cast. Changes require APPLY. Bosses and protected targets are excluded from damage overrides.",async fields=>await FeatureRequest("powers",fields,powerPanel));
            powerPanel.Flag("noMpCost","No MP cost for supported skill casts"); powerPanel.Flag("noAmmo","No ammo consumption (compatible ammo required)"); powerPanel.Flag("zeroCooldown","Zero cooldown for selected learned skills");
            powerPanel.TextField("cooldownSkills","Cooldown skill IDs (comma separated)"); powerPanel.TextField("immunity","Immunity: POISON, STUN, SEAL, CURSE, SLOW, DARKNESS, WEAKEN, CONFUSE, SEDUCE, ZOMBIFY");
            powerPanel.Number("damageMultiplier","Ordinary-target damage multiplier",0,100,1); powerPanel.Flag("oneHit","One Hit (ordinary targets)"); powerPanel.Flag("accuracy","Hit guarantee (ordinary targets)"); powerPanel.Choice("roll","Estimated damage: min 50%, avg 75%, max 100%",new[]{"normal","minimum","average","maximum"});
            powerPanel.ActionButton("CLEANSE",async()=>await FeatureRequest("cleanse",new Dictionary<string,string>(),null)); powerPanel.ActionButton("REFILL HP",async()=>await FeatureRequest("refill",new Dictionary<string,string>{{"resource","hp"}},null)); powerPanel.ActionButton("REFILL MP",async()=>await FeatureRequest("refill",new Dictionary<string,string>{{"resource","mp"}},null)); tabs.TabPages.Add(powerPanel);
            lootToolsPanel=new FeaturePanel("[ PET / LOOT+ ]","Ordinary nearby auto-loot works without Vac. Pet modes require a real summoned pet and pouch/magnet. Feeding consumes Pet Food. Automation pauses in dialogue/trade and resets on map change.",async fields=>await FeatureRequest("loottools",fields,lootToolsPanel));
            lootToolsPanel.Flag("nearbyAuto","Ordinary auto-loot within 180 pixels"); lootToolsPanel.Flag("petItems","Pet item vac"); lootToolsPanel.Flag("petMesos","Pet meso vac"); lootToolsPanel.Number("petIndex","Selected pet slot (0, 1 or 2)",0,2,0);
            lootToolsPanel.Flag("feeder","Feed selected pet with real Pet Food"); lootToolsPanel.Number("feedThreshold","Feed at fullness <=",1,75,50); lootToolsPanel.TextField("name","Item name contains"); lootToolsPanel.Choice("category","Item category",new[]{"all","equip","scroll","star","chair","use","etc"}); lootToolsPanel.Number("minValue","Minimum estimated item value (unknown excluded)",0,Int32.MaxValue,0);
            lootToolsPanel.Choice("source","Drop source",new[]{"all","bot","venue","player","monster"}); lootToolsPanel.Number("itemQuota","Item quota per sweep",0,25,25); lootToolsPanel.Number("mesoQuota","Meso quota per sweep",0,25,25); tabs.TabPages.Add(lootToolsPanel);
            mobToolsPanel=new FeaturePanel("[ MOB TOOLS ]","Ordinary mobs only; bosses, incidents and event instances retain normal behavior. Freeze blocks controlled movement/actions. Disarm blocks monster attacks. Point options use the Main MOB VAC switch.",async fields=>await FeatureRequest("mobtools",fields,mobToolsPanel));
            mobToolsPanel.Flag("freeze","Freeze ordinary mob movement / actions"); mobToolsPanel.Flag("disarm","Disarm ordinary mob attacks / skills"); mobToolsPanel.Flag("aggro","Keep ordinary aggro on this character");
            mobToolsPanel.Number("radius","Radius (0 = entire current map)",0,3000,0); mobToolsPanel.TextField("include","Only these mob IDs (blank = any)"); mobToolsPanel.TextField("exclude","Exclude these mob IDs");
            mobToolsPanel.Flag("pointVac","Use saved point for MOB VAC"); mobToolsPanel.Number("x","Saved map X",Int16.MinValue,Int16.MaxValue,0); mobToolsPanel.Number("y","Saved map Y",Int16.MinValue,Int16.MaxValue,0);
            mobToolsPanel.Number("spacing","Spacing between mobs",0,100,17); mobToolsPanel.Number("pullStep","Maximum pull step (0 = snap)",0,500,0); tabs.TabPages.Add(mobToolsPanel);
            regenPanel=new FeaturePanel("[ REGEN RATE ]","HP/MP activation uses the Survival switches. Set percent of maximum per tick and tick interval here. Rates apply immediately; profiles save them. Minimum one point per enabled tick. No catch-up bursts.",async fields=>await FeatureRequest("regenoptions",fields,regenPanel));
            regenPanel.Number("hpPercent","HP restored per tick (%)",1,25,10); regenPanel.Number("mpPercent","MP restored per tick (%)",1,25,10); regenPanel.Number("interval","Regeneration interval (ms)",250,5000,1000); tabs.TabPages.Add(regenPanel);
            pickupPanel=new FeaturePanel("[ PICKUP ]","Tubi repeats nearby pickup without extending reach. Burst is bounded to 25 attempts per 500 ms across all trainer triggers. Scenario age only affects tagged finite stakes; ownership stays normal. Equipment minima use actual dropped stats.",async fields=>await FeatureRequest("pickupoptions",fields,pickupPanel));
            pickupPanel.Flag("tubi","Tubi: repeated pickup at feet"); pickupPanel.Flag("burst","Super Tubi: repeated bounded burst queue"); pickupPanel.Number("interval","Requested pickup interval (ms)",50,1000,150); pickupPanel.Number("batch","Burst batch (global budget still applies)",1,25,10);
            pickupPanel.Flag("scenarioAge","Override tagged scenario spawn age"); pickupPanel.Number("minimumAge","Minimum tagged-drop age (ms)",0,400,400); pickupPanel.Number("minWatk","Equipment minimum weapon attack",0,32767,0); pickupPanel.Number("minMatk","Equipment minimum magic attack",0,32767,0); pickupPanel.Number("minSlots","Equipment minimum remaining upgrade slots",0,15,0); tabs.TabPages.Add(pickupPanel);
            var inspector=new TabPage("[ INSPECTOR ]"); inspector.BackColor=BackColor; inspector.ForeColor=neon;
            inspectorKind.SetBounds(12,5,150,25); inspectorKind.DropDownStyle=ComboBoxStyle.DropDownList; inspectorKind.Items.AddRange(new[]{"mobs","drops","portals","footholds"}); inspectorKind.SelectedIndex=0; inspector.Controls.Add(inspectorKind);
            var inspectRefresh=new Button(); inspectRefresh.Text="REFRESH"; Style(inspectRefresh); inspectRefresh.SetBounds(173,4,110,28); inspector.Controls.Add(inspectRefresh); inspectRefresh.Click+=async delegate { inspectorOffset=0; await RefreshInspector(); };
            var inspectPrevious=new Button(); inspectPrevious.Text="<"; Style(inspectPrevious); inspectPrevious.SetBounds(290,4,42,28); inspector.Controls.Add(inspectPrevious); inspectPrevious.Click+=async delegate { inspectorOffset=Math.Max(0,inspectorOffset-8); await RefreshInspector(); };
            var inspectNext=new Button(); inspectNext.Text=">"; Style(inspectNext); inspectNext.SetBounds(338,4,42,28); inspector.Controls.Add(inspectNext); inspectNext.Click+=async delegate { if(inspection!=null&&inspectorOffset+8<Int32.Parse(inspection["total"])) { inspectorOffset+=8; await RefreshInspector(); } };
            inspectorKind.SelectedIndexChanged+=delegate { inspectorOffset=0; inspection=null; inspectorRows.Rows.Clear(); inspectorCanvas.Invalidate(); };
            inspectorStatus.SetBounds(390,6,220,28); inspectorStatus.Text="Read-only / 8 objects per page"; inspector.Controls.Add(inspectorStatus);
            inspectorCanvas.SetBounds(12,40,240,165); inspectorCanvas.BackColor=Color.Black; inspectorCanvas.Paint+=DrawInspector; inspector.Controls.Add(inspectorCanvas);
            inspectorRows.SetBounds(260,40,351,165); inspectorRows.ReadOnly=true; inspectorRows.AllowUserToAddRows=false; inspectorRows.AllowUserToDeleteRows=false; inspectorRows.RowHeadersVisible=false; inspectorRows.SelectionMode=DataGridViewSelectionMode.FullRowSelect; inspectorRows.MultiSelect=false; inspectorRows.BackgroundColor=Color.Black; inspectorRows.DefaultCellStyle.BackColor=Color.Black; inspectorRows.DefaultCellStyle.ForeColor=neon;
            foreach(string title in new[]{"Object","ID / destination","Name","X","Y","HP / value / endpoint","Distance"}) inspectorRows.Columns.Add(title,title);
            inspectorRows.SelectionChanged+=delegate { inspectorCanvas.Invalidate(); }; inspector.Controls.Add(inspectorRows); tabs.TabPages.Add(inspector);
            var statistics=new TabPage("[ SESSION ]"); statistics.BackColor=BackColor; statistics.ForeColor=neon;
            sessionStats.Dock=DockStyle.Fill; sessionStats.Padding=new Padding(12); sessionStats.Text="Attach to start a fresh statistics session."; statistics.Controls.Add(sessionStats); tabs.TabPages.Add(statistics);
            var observations=new TabPage("[ ROLEPLAY ]"); observations.BackColor=BackColor; observations.ForeColor=neon;
            showObservations.Text="Show diagnostic observations (default hidden)"; showObservations.SetBounds(18,8,440,28); observations.Controls.Add(showObservations);
            var refreshObservations=new Button(); refreshObservations.Text="REFRESH"; Style(refreshObservations); refreshObservations.SetBounds(470,8,138,28); observations.Controls.Add(refreshObservations);
            observationLog.SetBounds(18,44,590,166); Input(observationLog); observationLog.Multiline=true; observationLog.ReadOnly=true; observationLog.ScrollBars=ScrollBars.Both; observationLog.WordWrap=false; observationLog.Visible=false; observations.Controls.Add(observationLog);
            showObservations.CheckedChanged+=delegate { observationLog.Visible=showObservations.Checked; if(!showObservations.Checked) observationLog.Clear(); };
            refreshObservations.Click+=async delegate { await RefreshObservations(); }; tabs.TabPages.Add(observations);
            var about=new TabPage("NFO / queued"); about.BackColor=BackColor; about.ForeColor=neon;
            var notes=new Label(); notes.Dock=DockStyle.Fill; notes.Padding=new Padding(12); notes.Text="CLICK EACH SWITCH TO APPLY IMMEDIATELY.\r\n\r\nMob Vac: real monsters group ahead of your facing direction.\r\nItem/Meso Vac: eligible drops, full map or radius, auto/key sweep.\r\nFMA expands real close-range swings, including empty swings.\r\nMouse Fly uses the separate client adapter.\r\nNo automatic attack key input: your movement and attack key stay yours."; about.Controls.Add(notes); tabs.TabPages.Add(about); Controls.Add(tabs);
#if CRITICAL_RELEASE
            Text="[xX_SoloH4x_Xx] SoloTrainer v0.6 - Critical release";
            // Owner narrowed this release to the original working powers and fixes.
            // Keep initialized draft panels for reset compatibility, outside this UI.
            for(int i=tabs.TabPages.Count-1;i>=0;--i) {
                var page=tabs.TabPages[i];
                if(page!=main&&page!=combat&&page!=mobs&&page!=movement&&page!=loot&&page!=survival&&page!=about)
                    tabs.TabPages.Remove(page);
            }
            fallThrough.Visible=hover.Visible=applyFlyOptions.Visible=flyVertical.Visible=false;
            flySpeed.Visible=flyDeadZone.Visible=flyInertia.Visible=flightSettings.Visible=false;
            autoHp.Visible=autoMp.Visible=applyAutoPotion.Visible=false;
            notes.Text="Original powers + critical fixes.\r\n\r\nINJECT HAX attaches to your existing client.\r\nMob Vac, Item/Meso pickup, close-range FMA and HP/MP controls.\r\nMouse Fly: toggle F6, hold ALT in the playfield to steer.\r\nRapid Attack removes local recovery; attack with your normal key.\r\nALL OFF restores native controls and clears server powers.";
#endif
            Check(main,vac,"[01] MOB VAC   // group monsters in front",12,11);
            Check(main,fma,"[02] FULL MAP ATTACK   // real close-range swing, <=100 mobs",12,44);
            var inputNote=new Label(); inputNote.Text="Attack normally: empty swings now expand across the map."; inputNote.SetBounds(18,86,580,24); inputNote.ForeColor=Color.Cyan; main.Controls.Add(inputNote);
            Check(combat,fmaOneHit,"FMA ONE HIT   // ordinary mobs die through normal loot/EXP path",12,14);
            var damageLabel=new Label(); damageLabel.Text="FMA DAMAGE x (199,999 cap):"; damageLabel.SetBounds(18,60,230,25); combat.Controls.Add(damageLabel);
            fmaDamage.SetBounds(248,57,95,26); fmaDamage.Minimum=1; fmaDamage.Maximum=100; fmaDamage.Value=1; fmaDamage.BackColor=Color.Black; fmaDamage.ForeColor=neon; combat.Controls.Add(fmaDamage);
            Check(combat,unlimited,"UNLIMITED ATTACK   // no stationary swing lock",12,94);
            unlimited.CheckedChanged+=async delegate { await SetUnlimited(); };
            Check(combat,rapid,"RAPID ATTACK   // real swings, no local recovery",12,126);
            rapid.CheckedChanged+=async delegate { await SetRapid(); };
            var cadenceLabel=new Label(); cadenceLabel.Text="Rapid interval (ms):"; cadenceLabel.SetBounds(18,163,180,24); combat.Controls.Add(cadenceLabel);
            attackInterval.SetBounds(205,160,90,26); attackInterval.Minimum=150; attackInterval.Maximum=1000; attackInterval.Increment=50; attackInterval.Value=300; attackInterval.BackColor=Color.Black; attackInterval.ForeColor=neon; combat.Controls.Add(attackInterval);
            var combatNote=new Label(); combatNote.Text="Candidate hook. Verify real cast rate and damage in game."; combatNote.SetBounds(315,158,300,46); combatNote.ForeColor=Color.Cyan; combat.Controls.Add(combatNote);
            apply.Text="APPLY H4X"; Style(apply); apply.SetBounds(410,114,200,30); main.Controls.Add(apply); apply.Click+=async delegate { await Configure(); };
            foreach(var toggle in new[]{vac,itemVac,mesoVac,lootOnKey,fma,fmaOneHit,hpGod,hpRegen,mpRegen}) toggle.CheckedChanged+=delegate { QueueConfigure(); };
            foreach(var number in new[]{fmaDamage,lootRadius,lootBatch,attackInterval}) number.ValueChanged+=delegate { QueueConfigure(); };
            vacMode.SelectedIndexChanged+=delegate { QueueConfigure(); };
            counters.SetBounds(18,165,610,45); counters.Text="Mobs: 0    Drops: 0    Extra hits: 0    Swings: 0"; main.Controls.Add(counters);
            connect.Text=">>> INJECT HAX <<<"; Style(connect); connect.Font=new Font("Impact",22f,FontStyle.Italic); connect.BackColor=Color.FromArgb(89,13,13); connect.ForeColor=Color.Lime; connect.SetBounds(16,403,647,52); Controls.Add(connect); connect.Click+=async delegate { await Attach(); };
            status.SetBounds(16,460,448,37); status.Text="READY - click INJECT HAX with the game logged in."; status.ForeColor=Color.Orange; Controls.Add(status);
            panic.Text="PANIC: ALL OFF\r\nCTRL + F12"; Style(panic); panic.ForeColor=Color.Red; panic.SetBounds(480,458,183,40); Controls.Add(panic); panic.Click+=async delegate { await Off(); };
            log.SetBounds(16,507,647,50); Input(log); log.Multiline=true; log.ReadOnly=true; log.ScrollBars=ScrollBars.Vertical; log.Text="[nfo] staged test build / own server and optional client adapter\r\n";
            SetControls(false); timer.Interval=1000; timer.Tick+=async delegate { if(paired&&!busy) await Heartbeat(); }; timer.Start();
            shortcutTimer.Interval=25; shortcutTimer.Tick+=delegate { PollFlyShortcut(); }; shortcutTimer.Start();
            FormClosing+=delegate { closing=true; timer.Stop(); shortcutTimer.Stop(); if(adapter!=null) { var oldAdapter=adapter; adapter=null; Task.Run(delegate { oldAdapter.Dispose(); }); } if(bridge!=null&&bridge.Token!=null) { var old=bridge; Task.Run(delegate { try { old.Send("off",new Dictionary<string,string>()); } catch {} }); } };
            Shown+=delegate { if(ShowInTaskbar) { if(!RegisterHotKey(Handle,83,0x4002,0x7B)) Append("Global panic hotkey unavailable; use visible ALL OFF."); if(!RegisterHotKey(Handle,84,0x4002,0x7A)) Append("Global loot hotkey unavailable; use SUCC NOW."); } };
        }
        protected override void OnFormClosed(FormClosedEventArgs e) { UnregisterHotKey(Handle,83); UnregisterHotKey(Handle,84); timer.Dispose(); base.OnFormClosed(e); }
        protected override void WndProc(ref Message m) { if(m.Msg==0x312&&m.WParam.ToInt32()==83) { var ignored=Off(); } if(m.Msg==0x312&&m.WParam.ToInt32()==84) { var ignored=PulseLoot(); } base.WndProc(ref m); }
        private Label LabelAt(string text,int x,int y,int w,int h) { var l=new Label(); l.Text=text; l.SetBounds(x,y,w,h); Controls.Add(l); return l; }
        private void PotionNumber(Control parent,NumericUpDown input,int x,int y,int min,int max,int value) { input.SetBounds(x,y,90,25); input.Minimum=min; input.Maximum=max; input.Value=value; input.BackColor=Color.Black; input.ForeColor=neon; parent.Controls.Add(input); }
        private void Check(Control p,CheckBox c,string text,int x,int y) { c.Text=text; c.SetBounds(x,y,610,29); c.FlatStyle=FlatStyle.Flat; c.ForeColor=neon; p.Controls.Add(c); }
        private void Input(TextBox t) { t.BackColor=Color.Black; t.ForeColor=Color.Lime; t.BorderStyle=BorderStyle.FixedSingle; Controls.Add(t); }
        private void Style(Button b) { b.BackColor=Color.FromArgb(37,44,32); b.ForeColor=neon; b.FlatStyle=FlatStyle.Flat; b.FlatAppearance.BorderColor=Color.OliveDrab; b.Font=new Font("Consolas",10f,FontStyle.Bold); }
        private void SetControls(bool enabled) {
            // WinForms paints disabled checkbox text almost black on this dark skin.
            // Keep labels readable while preventing edits until the server is paired.
            vac.AutoCheck=itemVac.AutoCheck=mesoVac.AutoCheck=lootOnKey.AutoCheck=fma.AutoCheck=fmaOneHit.AutoCheck=hpGod.AutoCheck=hpRegen.AutoCheck=mpRegen.AutoCheck=enabled;
            fly.AutoCheck=enabled&&adapter!=null&&adapter.FlyReady&&!flyBusy;
            unlimited.AutoCheck=enabled&&adapter!=null&&adapter.UnlimitedReady&&!unlimitedBusy;
            rapid.AutoCheck=enabled&&adapter!=null&&adapter.RapidReady&&!rapidBusy;
            skillEffects.AutoCheck=enabled&&adapter!=null&&adapter.SkillEffectsReady&&!skillEffectsBusy;
            fallThrough.AutoCheck=enabled&&adapter!=null&&adapter.FallThroughReady&&!fallThroughBusy;
            hover.AutoCheck=enabled&&adapter!=null&&adapter.HoverReady&&!extraNativeBusy;
            cpuMode.AutoCheck=enabled&&adapter!=null&&adapter.CpuReady&&!extraNativeBusy;
            backgroundFps.Enabled=enabled&&adapter!=null&&adapter.CpuReady&&!extraNativeBusy;
            attackInterval.Enabled=fmaDamage.Enabled=lootRadius.Enabled=lootBatch.Enabled=minMeso.Enabled=maxMeso.Enabled=includeIds.Enabled=excludeIds.Enabled=lootOrder.Enabled=vacMode.Enabled=enabled;
            apply.Enabled=lootNow.Enabled=savePreset.Enabled=loadPreset.Enabled=enabled;
            flySpeed.Enabled=flyDeadZone.Enabled=flyInertia.Enabled=flyVertical.Enabled=applyFlyOptions.Enabled=enabled&&adapter!=null&&adapter.FlyConfigReady;
            autoHp.AutoCheck=autoMp.AutoCheck=enabled&&autoPotionReady;
            autoHpThreshold.Enabled=autoMpThreshold.Enabled=autoReserve.Enabled=autoInterval.Enabled=autoHpItem.Enabled=autoMpItem.Enabled=applyAutoPotion.Enabled=enabled&&autoPotionReady;
            if(powerPanel!=null) powerPanel.EnableEditing(enabled); if(lootToolsPanel!=null) lootToolsPanel.EnableEditing(enabled); if(mobToolsPanel!=null) mobToolsPanel.EnableEditing(enabled); if(pickupPanel!=null) pickupPanel.EnableEditing(enabled); if(regenPanel!=null) regenPanel.EnableEditing(enabled);
        }
        private void QueueConfigure() {
            if(syncing||!paired||panicBusy) return;
            pendingConfiguration=true;
            if(!busy) { var ignored=Configure(); }
        }
        private void Append(string message) {
            string line="["+DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff")+"] "+message;
            try { if(File.Exists(logPath)&&new FileInfo(logPath).Length>1024*1024) { if(File.Exists(logPath+".previous")) File.Delete(logPath+".previous"); File.Move(logPath,logPath+".previous"); } File.AppendAllText(logPath,line+Environment.NewLine); } catch {}
            if(!closing) { if(log.TextLength>32000) log.Text=log.Text.Substring(log.TextLength-16000); log.AppendText(line.Replace("\r"," ").Replace("\n"," ")+"\r\n"); }
        }
        private string PresetPath() {
            if(String.IsNullOrEmpty(characterName)) throw new InvalidDataException("Attach to a character first.");
            string identityHash=Wire.Hash(Encoding.UTF8.GetBytes(characterName)).Substring(0,24);
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"SoloMapling","Trainer",identityHash+"-"+presetChoice.SelectedIndex+".preset");
        }
        private Dictionary<string,string> CapturePotions() {
            return new Dictionary<string,string>{{"hp",autoHp.Checked?"1":"0"},{"mp",autoMp.Checked?"1":"0"},{"hpThreshold",autoHpThreshold.Value.ToString()},{"mpThreshold",autoMpThreshold.Value.ToString()},{"hpItem",autoHpItem.Text.Split(' ')[0]},{"mpItem",autoMpItem.Text.Split(' ')[0]},{"reserve",autoReserve.Value.ToString()},{"interval",autoInterval.Value.ToString()}};
        }
        private Dictionary<string,string> CaptureProfile() {
            return new Dictionary<string,string>{{"core",Wire.Encode(CaptureSettings())},{"potions",Wire.Encode(CapturePotions())},{"powers",Wire.Encode(powerPanel.CaptureFields())},{"loot",Wire.Encode(lootToolsPanel.CaptureFields())},{"mobs",Wire.Encode(mobToolsPanel.CaptureFields())},{"pointMap",currentMap},{"pickup",Wire.Encode(pickupPanel.CaptureFields())},{"regen",Wire.Encode(regenPanel.CaptureFields())},
                {"native",Wire.Encode(new Dictionary<string,string>{{"fly",fly.Checked?"1":"0"},{"hover",hover.Checked?"1":"0"},{"fallThrough",fallThrough.Checked?"1":"0"},{"unlimited",unlimited.Checked?"1":"0"},{"skillEffects",skillEffects.Checked?"1":"0"},{"cpu",cpuMode.Checked?"1":"0"},{"fps",backgroundFps.Value.ToString()},{"flySpeed",flySpeed.Value.ToString()},{"flyDeadZone",flyDeadZone.Value.ToString()},{"flyInertia",flyInertia.Value.ToString()},{"flyVertical",flyVertical.Checked?"1":"0"}})}};
        }
        private void SavePreset() {
            if(!paired||busy||panicBusy) return;
            try { PresetFile.Save(PresetPath(),PresetFile.EncodeProfile(characterName,CaptureProfile())); Append("Saved complete "+presetChoice.Text+" profile for "+characterName+". Explicit Apply only."); }
            catch(Exception error) { Append("Preset not saved: "+error.Message); }
        }
        private void ValidateNativeProfile(Dictionary<string,string> native,Dictionary<string,string> core) {
            if(native["fly"]=="1"&&(adapter==null||!adapter.FlyConfigReady)) throw new InvalidDataException("Preset flight requires the matching held-steering adapter.");
            string[] keys={"fly","hover","fallThrough","unlimited","skillEffects","cpu","rapid"};
            bool[] capabilities={adapter!=null&&adapter.FlyReady,adapter!=null&&adapter.HoverReady,adapter!=null&&adapter.FallThroughReady,adapter!=null&&adapter.UnlimitedReady,adapter!=null&&adapter.SkillEffectsReady,adapter!=null&&adapter.CpuReady,adapter!=null&&adapter.RapidReady};
            for(int i=0;i<keys.Length;i++) if((keys[i]=="rapid"?core["rapid"]:native[keys[i]])=="1"&&!capabilities[i]) throw new InvalidDataException("Required client capability unavailable: "+keys[i]);
        }
        private async Task ApplyPreset(bool previewOnly=false) {
            if(!paired||busy||panicBusy||rapidBusy||flyBusy||unlimitedBusy||skillEffectsBusy||fallThroughBusy||extraNativeBusy) return;
            bool attemptedApply=false,resetNeeded=false; int requestEpoch=epoch;
            try {
                string path=PresetPath(); if(!File.Exists(path)) throw new InvalidDataException("This preset slot is empty.");
                if(new FileInfo(path).Length>8192) throw new InvalidDataException("Preset is too large.");
                string encoded=File.ReadAllText(path);
                if(Wire.Decode(encoded)["version"]=="1") {
                    var legacy=PresetFile.Decode(encoded,characterName); legacy.Add("rapid",rapid.Checked?"1":"0");
                    if(previewOnly) profilePreview.Text="Legacy core settings only: "+Wire.Encode(legacy); else await Configure(legacy); return;
                }
                if(!profileReady) throw new InvalidDataException("Complete presets require matching server capability.");
                var sections=PresetFile.DecodeProfile(encoded,characterName); var native=Wire.Decode(sections["native"]); var core=Wire.Decode(sections["core"]);
                powerPanel.ValidateFields(Wire.Decode(sections["powers"])); lootToolsPanel.ValidateFields(Wire.Decode(sections["loot"])); mobToolsPanel.ValidateFields(Wire.Decode(sections["mobs"])); pickupPanel.ValidateFields(Wire.Decode(sections["pickup"])); regenPanel.ValidateFields(Wire.Decode(sections["regen"])); ValidateNativeProfile(native,core);
                sections.Remove("native"); busy=true; profileApplying=true; pendingConfiguration=false; SetControls(false);
                var b=bridge; var a=adapter;
                await Task.Run(delegate { return b.Send("profileValidate",sections); });
                if(closing||requestEpoch!=epoch) return;
                var preview=new StringBuilder(); var current=CaptureProfile();
                foreach(var section in sections) {
                    if(section.Key=="pointMap") { preview.AppendLine("Saved point map: "+section.Value); continue; }
                    var before=Wire.Decode(current[section.Key]); foreach(var value in Wire.Decode(section.Value)) if(!before.ContainsKey(value.Key)||before[value.Key]!=value.Value) preview.AppendLine(section.Key+" / "+value.Key+": "+(before.ContainsKey(value.Key)?before[value.Key]:"?")+" -> "+value.Value);
                }
                foreach(var value in native) preview.AppendLine("native / "+value.Key+": "+value.Value);
                profilePreview.Text=preview.ToString();
                if(previewOnly) { Append("Profile validated; no settings changed."); return; }
                attemptedApply=true;
                var result=await Task.Run(delegate { return b.Send("profile",sections); });
                if(closing||requestEpoch!=epoch) return;
                if(a!=null) {
                    if(a.FlyConfigReady) {
                        string command="FLYOPT "+native["flySpeed"]+" "+native["flyDeadZone"]+" "+native["flyInertia"]+" "+native["flyVertical"];
                        string response=await Task.Run(delegate { return a.Send(command); });
                        if(closing||requestEpoch!=epoch) return;
                        if(response!="OK FLYOPT") throw new IOException(response);
                    }
                    string[] commands={"FLY","HOVER","FALLTHROUGH","UNLIMITED","SKILLFX","CPU","RAPID"};
                    string[] keys={"fly","hover","fallThrough","unlimited","skillEffects","cpu","rapid"};
                    bool[] capabilities={a.FlyReady,a.HoverReady,a.FallThroughReady,a.UnlimitedReady,a.SkillEffectsReady,a.CpuReady,a.RapidReady};
                    // Disable supported modes first, then enable the validated composition.
                    int completed=0;
                    for(int pass=0;pass<2;pass++) for(int i=0;i<keys.Length;i++) {
                        if(!capabilities[i]) continue; string value=keys[i]=="rapid"?core["rapid"]:native[keys[i]];
                        if(pass==1&&value=="0") continue;
                        string bit=pass==0?"0":value, command=commands[i]+" "+bit+(keys[i]=="cpu"?" "+native["fps"]:"");
                        string expected="OK "+commands[i]+"="+bit;
                        string response=await Task.Run(delegate { return a.Send(command); });
                        if(closing||requestEpoch!=epoch) return;
                        if(response!=expected) throw new IOException("Native profile rejected: "+response);
                        if(++completed%3==0) await Task.Run(delegate { return b.Send("status",new Dictionary<string,string>()); });
                    }
                }
                string appliedMap=result["map"];
                result=await Task.Run(delegate { return b.Send("status",new Dictionary<string,string>()); });
                if(closing||requestEpoch!=epoch) return;
                if(result["map"]!=appliedMap) throw new IOException("Map changed during profile application; apply again in the new map.");
                filtersInitialized=false; autoPotionDraft=false; powerPanel.Accepted(); lootToolsPanel.Accepted(); mobToolsPanel.Accepted(); pickupPanel.Accepted(); regenPanel.Accepted(); UpdateState(result);
                syncing=true; try { fly.Checked=native["fly"]=="1"; hover.Checked=native["hover"]=="1"; fallThrough.Checked=native["fallThrough"]=="1"; unlimited.Checked=native["unlimited"]=="1"; skillEffects.Checked=native["skillEffects"]=="1"; cpuMode.Checked=native["cpu"]=="1"; backgroundFps.Value=Int32.Parse(native["fps"]); flySpeed.Value=Int32.Parse(native["flySpeed"]); flyDeadZone.Value=Int32.Parse(native["flyDeadZone"]); flyInertia.Value=Int32.Parse(native["flyInertia"]); flyVertical.Checked=native["flyVertical"]=="1"; } finally { syncing=false; }
                Append("Complete profile acknowledged by server and client. Gameplay acceptance pending.");
            } catch(Exception error) {
                Append("Preset not applied: "+error.Message);
                if(attemptedApply&&requestEpoch==epoch) { Append("Profile application failed; resetting all powers."); resetNeeded=true; }
            } finally { busy=false; profileApplying=false; if(!closing&&requestEpoch==epoch&&!resetNeeded) SetControls(paired); }
            if(resetNeeded) await Off();
        }
        private async Task RefreshInspector() {
            if(!paired||busy||panicBusy) return;
            busy=true; int requestEpoch=epoch; string map=currentMap,kind=inspectorKind.Text; int offset=inspectorOffset;
            try { var b=bridge; var result=await Task.Run(delegate { return b.Send("inspect",new Dictionary<string,string>{{"map",map},{"kind",kind},{"offset",offset.ToString()}}); });
                if(closing||requestEpoch!=epoch||currentMap!=map||inspectorKind.Text!=kind) return;
                int count=Int32.Parse(result["count"]); if(count<0||count>8||result["map"]!=map||result["kind"]!=kind) throw new IOException("Invalid inspector page");
                inspectorRows.Rows.Clear();
                for(int i=0;i<count;i++) { string[] row=result["row"+i].Split('\t'); if(row.Length!=7) throw new IOException("Invalid inspector row"); Int32.Parse(row[3]); Int32.Parse(row[4]); inspectorRows.Rows.Add(row); }
                inspection=result; inspectorStatus.Text=(offset+1)+"-"+(offset+count)+" / "+result["total"]+" (snapshot)"; inspectorCanvas.Invalidate();
            } catch(Exception error) { if(requestEpoch==epoch) inspectorStatus.Text=error.Message; }
            finally { busy=false; }
        }
        private void DrawInspector(object sender,PaintEventArgs e) {
            if(inspection==null) { e.Graphics.DrawString("Refresh to inspect this map.\nMarkers show this page only.",Font,Brushes.Lime,8,8); return; }
            try {
                string[] area=inspection["area"].Split(','),actor=inspection["actor"].Split(',');
                float x=Single.Parse(area[0]),y=Single.Parse(area[1]),w=Math.Max(1,Single.Parse(area[2])),h=Math.Max(1,Single.Parse(area[3]));
                float scale=Math.Min((inspectorCanvas.Width-16)/w,(inspectorCanvas.Height-16)/h);
                Func<float,float,PointF> at=delegate(float px,float py) { return new PointF(8+(px-x)*scale,8+(py-y)*scale); };
                e.Graphics.DrawRectangle(Pens.DimGray,8,8,w*scale,h*scale);
                PointF player=at(Single.Parse(actor[0]),Single.Parse(actor[1])); e.Graphics.FillEllipse(Brushes.Cyan,player.X-3,player.Y-3,6,6);
                for(int i=0;i<inspectorRows.Rows.Count;i++) {
                    var row=inspectorRows.Rows[i]; PointF point=at(Single.Parse(row.Cells[3].Value.ToString()),Single.Parse(row.Cells[4].Value.ToString()));
                    var pen=row.Selected?Pens.Yellow:Pens.Lime;
                    if(inspection["kind"]=="footholds") { string[] end=row.Cells[5].Value.ToString().Split(','); e.Graphics.DrawLine(pen,point,at(Single.Parse(end[0]),Single.Parse(end[1]))); }
                    else e.Graphics.DrawRectangle(pen,point.X-3,point.Y-3,6,6);
                }
            } catch(Exception) { e.Graphics.DrawString("Invalid map snapshot",Font,Brushes.Red,8,8); }
        }
        private async Task RefreshObservations() {
            if(!paired||busy||panicBusy||!showObservations.Checked) return;
            busy=true; int requestEpoch=epoch;
            try { var b=bridge; var result=await Task.Run(delegate { return b.Send("observations",new Dictionary<string,string>()); });
                if(closing||requestEpoch!=epoch||!showObservations.Checked) return;
                int count=Int32.Parse(result["observationCount"]); if(count<0||count>12) throw new IOException("Invalid observation count");
                var text=new StringBuilder(result["observationStatus"]+Environment.NewLine);
                for(int i=0;i<count;i++) text.AppendLine(result["observation"+i]); observationLog.Text=text.ToString();
            } catch(Exception error) { if(requestEpoch==epoch) observationLog.Text="Could not refresh: "+error.Message; }
            finally { busy=false; }
        }
        private async Task ApplyAutoPotion() {
            if(!paired||!autoPotionReady||busy||panicBusy) return;
            var fields=new Dictionary<string,string>{{"hp",autoHp.Checked?"1":"0"},{"mp",autoMp.Checked?"1":"0"},{"hpThreshold",autoHpThreshold.Value.ToString()},{"mpThreshold",autoMpThreshold.Value.ToString()},{"hpItem",autoHpItem.Text.Split(' ')[0]},{"mpItem",autoMpItem.Text.Split(' ')[0]},{"reserve",autoReserve.Value.ToString()},{"interval",autoInterval.Value.ToString()}};
            busy=true; SetControls(false); int requestEpoch=epoch;
            try {
                var b=bridge; var result=await Task.Run(delegate { return b.Send("autopotion",fields); });
                if(!closing&&requestEpoch==epoch) { autoPotionDraft=false; UpdateState(result); Append("Auto potion server settings confirmed. Real inventory consumption still requires live verification."); }
            } catch(Exception error) { if(requestEpoch==epoch) Disconnect(error.Message); }
            finally { busy=false; if(!closing&&requestEpoch==epoch) SetControls(paired); }
        }
        private async Task FeatureRequest(string operation,Dictionary<string,string> fields,FeaturePanel panel) {
            if(!paired||busy||panicBusy) return;
            busy=true; SetControls(false); int requestEpoch=epoch;
            try { var b=bridge; var result=await Task.Run(delegate { return b.Send(operation,fields); });
                if(!closing&&requestEpoch==epoch) { if(panel!=null) panel.Accepted(); UpdateState(result); Append(operation+" confirmed by server; live effect verification pending."); }
            } catch(Exception error) { if(requestEpoch==epoch) Disconnect(error.Message); }
            finally { busy=false; if(!closing&&requestEpoch==epoch) SetControls(paired); }
        }
        private async Task Attach() {
            if(busy||panicBusy) return; busy=true; connect.Enabled=false; SetControls(false);
            int requestEpoch=epoch;
            try {
                var candidate=new Bridge();
                var result=await Task.Run(delegate { return candidate.Send("attach",new Dictionary<string,string>()); });
                if(closing) return;
                if(!result.ContainsKey("token")||result["token"].Length!=48) throw new IOException("Invalid attach response.");
                if(requestEpoch!=epoch) {
                    candidate.Token=result["token"];
                    await Task.Run(delegate { try { candidate.Send("off",new Dictionary<string,string>()); } catch {} });
                    return;
                }
                candidate.Token=result["token"];
                var active=await Task.Run(delegate { return candidate.Send("configure",new Dictionary<string,string>{{"vac","1"},{"vacMode","front"},{"itemVac","1"},{"mesoVac","1"},{"lootOnKey","0"},{"lootRadius","0"},{"lootBatch","8"},{"lootOrder","nearest"},{"includeIds",""},{"excludeIds",""},{"minMeso","0"},{"maxMeso",Int32.MaxValue.ToString()},{"fma","1"},{"fmaDamage","1"},{"fmaOneHit","0"},{"rapid","0"},{"hpGod","0"},{"hpRegen","0"},{"mpRegen","0"},{"interval","300"}}); });
                if(requestEpoch!=epoch) { await Task.Run(delegate { try { candidate.Send("off",new Dictionary<string,string>()); } catch {} }); return; }
                bridge=candidate; paired=true; lastLoggedFmaCast=0;
                var previousAdapter=adapter;
                var connectedAdapter=await Task.Run(delegate { return ClientAdapter.Reuse(previousAdapter)??ClientAdapter.TryConnect(); });
                if(closing||requestEpoch!=epoch) {
                    if(connectedAdapter!=null) await Task.Run(delegate { connectedAdapter.Dispose(); });
                    return;
                }
                adapter=connectedAdapter;
                if(adapter!=previousAdapter) {
                    syncing=true;
                    fly.Checked=unlimited.Checked=skillEffects.Checked=fallThrough.Checked=hover.Checked=cpuMode.Checked=false;
                    syncing=false;
                }
                UpdateState(active); SetControls(true);
                Append("HAX active: mob vac, item/meso vac, real-swing FMA. Attack input stays yours. Panic: visible ALL OFF.");
                Append(adapter==null ? "Client adapter unavailable; client controls remain off. "+ClientAdapter.LastError
                    : "Client adapter connected. Fly="+adapter.FlyReady+" Unlimited Attack="+adapter.UnlimitedReady+" Rapid Attack="+adapter.RapidReady);
            } catch(Exception e) { if(requestEpoch==epoch) { Append(e.ToString()); Disconnect(e.Message); } }
            finally { busy=false; if(!closing) connect.Enabled=!panicBusy; }
        }
        private Dictionary<string,string> CaptureSettings() {
            return new Dictionary<string,string>{{"vac",vac.Checked?"1":"0"},{"vacMode",vacMode.Text},{"itemVac",itemVac.Checked?"1":"0"},{"mesoVac",mesoVac.Checked?"1":"0"},{"lootOnKey",lootOnKey.Checked?"1":"0"},{"lootRadius",lootRadius.Value.ToString()},{"lootBatch",lootBatch.Value.ToString()},{"lootOrder",lootOrder.Text},{"includeIds",includeIds.Text},{"excludeIds",excludeIds.Text},{"minMeso",minMeso.Value.ToString()},{"maxMeso",maxMeso.Value.ToString()},{"fma",fma.Checked?"1":"0"},{"fmaDamage",fmaDamage.Value.ToString()},{"fmaOneHit",fmaOneHit.Checked?"1":"0"},{"rapid",rapid.Checked?"1":"0"},{"hpGod",hpGod.Checked?"1":"0"},{"hpRegen",hpRegen.Checked?"1":"0"},{"mpRegen",mpRegen.Checked?"1":"0"},{"interval",attackInterval.Value.ToString()}};
        }
        private async Task Configure(Dictionary<string,string> preset=null) {
            if(!paired) { status.Text="Click INJECT HAX first."; return; }
            if(busy||panicBusy) { pendingConfiguration=true; return; } busy=true; pendingConfiguration=false; SetControls(false); status.Text="PENDING server confirmation - not assumed active";
            int requestEpoch=epoch;
            var payload=preset??CaptureSettings();
            if(preset!=null) filtersInitialized=false;
            try { var b=bridge; var result=await Task.Run(delegate { return b.Send("configure",payload); }); if(!closing&&requestEpoch==epoch) { UpdateState(result); Append("Server confirmed settings; FMA follows real close-range swings."); } }
            catch(Exception e) { if(requestEpoch==epoch) { Append(e.ToString()); Disconnect(e.Message); } }
            finally { busy=false; if(!closing&&requestEpoch==epoch) { SetControls(paired); if(pendingConfiguration&&paired) { var ignored=Configure(); } } }
        }
        private async Task Heartbeat() {
            if(busy||!paired) return; busy=true;
            int requestEpoch=epoch;
            bool resetNeeded=false;
            try {
                var b=bridge; var result=await Task.Run(delegate { return b.Send("status",new Dictionary<string,string>()); });
                if(!closing&&requestEpoch==epoch) UpdateState(result);
                var a=adapter;
                if(a==null&&!closing&&requestEpoch==epoch) {
                    var recovered=await Task.Run(delegate { return ClientAdapter.TryConnect(); });
                    if(closing||requestEpoch!=epoch) {
                        if(recovered!=null) await Task.Run(delegate { recovered.Dispose(); });
                        return;
                    }
                    adapter=a=recovered;
                    if(a!=null) { SetControls(paired); Append("Client adapter reconnected; native controls available."); }
                }
                if(a!=null&&requestEpoch==epoch) {
                    try {
                        if(a.HoverReady&&lastNativeMap!=null&&lastNativeMap!=result["map"]) {
                            string cleared=await Task.Run(delegate { return a.Send("MOTIONOFF"); });
                            if(cleared!="OK MOTIONOFF") throw new IOException(cleared);
                        }
                        lastNativeMap=result["map"];
                        string ping=await Task.Run(delegate { return a.Send("PING"); });
                        if(!ping.StartsWith("OK ")) throw new IOException("Client adapter heartbeat invalid: "+ping);
                        if(a.MovementReset&&requestEpoch==epoch) {
                            a.MovementReset=false;
                            bool restoreFly=fly.Checked&&a.FlyConfigReady&&!flyBusy;
                            syncing=true; hover.Checked=fallThrough.Checked=false; if(!restoreFly) fly.Checked=false; syncing=false;
                            if(restoreFly) {
                                string options=FlyOptionsCommand();
                                string configured=await Task.Run(delegate { return a.Send(options); });
                                if(configured!="OK FLYOPT") throw new IOException(configured);
                                if(closing||requestEpoch!=epoch||adapter!=a||!fly.Checked||flyBusy) return;
                                string restored=await Task.Run(delegate { return a.Send("FLY 1"); });
                                if(restored!="OK FLY=1") throw new IOException(restored);
                                Append("Map transition reset movement; Mouse Fly restored. Hold ALT to steer.");
                            } else Append("Map transition cleared native movement. Trainer remains attached.");
                        }
                    } catch(Exception clientError) {
                        if(requestEpoch==epoch&&adapter==a) {
                            Append("Client adapter disconnected; revoking server profile. "+clientError.Message);
                            resetNeeded=true;
                        }
                    }
                }
                if(resetNeeded) await Off();
            }
            catch(Exception e) { if(requestEpoch==epoch) { Append(e.ToString()); Disconnect(e.Message); } }
            finally { busy=false; if(pendingConfiguration&&paired&&!closing) { var ignored=Configure(); } }
        }
        private async Task PulseLoot() {
            long now=DateTime.UtcNow.Ticks;
            if(!paired||busy||panicBusy||now-lastLootPulse<TimeSpan.TicksPerMillisecond*170) return;
            lastLootPulse=now;
            busy=true; int requestEpoch=epoch;
            try { var b=bridge; var result=await Task.Run(delegate { return b.Send("lootPulse",new Dictionary<string,string>()); }); if(!closing&&requestEpoch==epoch) { UpdateState(result); Append("Manual loot sweep confirmed."); } }
            catch(Exception e) { if(requestEpoch==epoch) { Append(e.ToString()); Disconnect(e.Message); } }
            finally { busy=false; if(pendingConfiguration&&paired&&!closing) { var ignored=Configure(); } }
        }
        private void PollFlyShortcut() {
            bool down=(GetAsyncKeyState((int)flyKey)&0x8000)!=0;
            uint focused; GetWindowThreadProcessId(GetForegroundWindow(),out focused);
            bool allowed=!closing&&paired&&!busy&&!profileApplying&&!panicBusy&&!flyBusy&&adapter!=null&&adapter.FlyReady
                && (focused==(uint)ownProcessId||focused==(uint)adapter.GameProcessId);
            if(!flyShortcut.Pressed(down,allowed)) return;
            Append(flyKey+" fly shortcut: "+(fly.Checked?"OFF":"ON"));
            fly.Checked=!fly.Checked;
        }
        private string FlyOptionsCommand() { return "FLYOPT "+flySpeed.Value+" "+flyDeadZone.Value+" "+flyInertia.Value+" "+(flyVertical.Checked?"1":"0"); }
        private async Task ApplyFlyOptions() {
            if(!paired||busy||panicBusy||adapter==null||!adapter.FlyConfigReady||flyBusy) return;
            busy=true; SetControls(false); int requestEpoch=epoch; var a=adapter; string command=FlyOptionsCommand(); bool reset=false;
            try { string reply=await Task.Run(delegate { return a.Send(command); }); if(requestEpoch!=epoch) return; if(reply!="OK FLYOPT") throw new IOException(reply); Append("Fly options acknowledged. Hold ALT over the playfield; release restores normal physics."); }
            catch(Exception error) { if(requestEpoch==epoch) { Append(error.Message); reset=true; } }
            finally { busy=false; if(requestEpoch==epoch&&!reset) SetControls(paired); }
            if(reset) await Off();
        }
        private async Task SetFly() {
            if(syncing||!paired||panicBusy||adapter==null||flyBusy) return;
            if(fly.Checked&&(fallThrough.Checked||hover.Checked)) { syncing=true; fly.Checked=false; syncing=false; Append("Turn Fall Through and Hover off before enabling Fly."); return; }
            flyBusy=true; fly.AutoCheck=false;
            bool desired=fly.Checked;
            int requestEpoch=epoch;
            var active=adapter;
            bool resetNeeded=false;
            try {
                if(desired&&active.FlyConfigReady) {
                    string options=FlyOptionsCommand(); string configured=await Task.Run(delegate { return active.Send(options); });
                    if(requestEpoch!=epoch||adapter!=active) return;
                    if(configured!="OK FLYOPT") throw new IOException(configured);
                }
                string result=await Task.Run(delegate { return active.Send(desired?"FLY 1":"FLY 0"); });
                if(requestEpoch!=epoch||adapter!=active) return;
                if(result!=(desired?"OK FLY=1":"OK FLY=0")) throw new IOException(result);
                Append(desired?"Mouse fly active; "+flyKey+" turns it off. Hold ALT over the playfield to steer; release to land.":"Mouse fly off; "+flyKey+" turns it on.");
            } catch(Exception error) {
                if(requestEpoch!=epoch||adapter!=active) return;
                Append("Mouse fly: "+error.Message);
                resetNeeded=true;
            } finally {
                flyBusy=false;
                fly.AutoCheck=paired&&adapter!=null&&adapter.FlyReady&&!panicBusy&&!resetNeeded;
            }
            if(resetNeeded) await Off();
        }
        private async Task SetUnlimited() {
            if(syncing||!paired||panicBusy||adapter==null||unlimitedBusy) return;
            unlimitedBusy=true; unlimited.AutoCheck=false;
            bool desired=unlimited.Checked;
            int requestEpoch=epoch;
            var active=adapter;
            bool resetNeeded=false;
            try {
                string result=await Task.Run(delegate { return active.Send(desired?"UNLIMITED 1":"UNLIMITED 0"); });
                if(requestEpoch!=epoch||adapter!=active) return;
                if(result!=(desired?"OK UNLIMITED=1":"OK UNLIMITED=0")) throw new IOException(result);
                Append(desired?"Unlimited Attack active; stationary swing lock removed.":"Unlimited Attack off.");
            } catch(Exception error) {
                if(requestEpoch!=epoch||adapter!=active) return;
                Append("Unlimited Attack: "+error.Message);
                resetNeeded=true;
            } finally {
                unlimitedBusy=false;
                unlimited.AutoCheck=paired&&adapter!=null&&adapter.UnlimitedReady&&!panicBusy&&!resetNeeded;
            }
            if(resetNeeded) await Off();
        }
        private async Task SetSkillEffects() {
            if(syncing||!paired||panicBusy||adapter==null||skillEffectsBusy) return;
            skillEffectsBusy=true; skillEffects.AutoCheck=false;
            bool desired=skillEffects.Checked;
            int requestEpoch=epoch;
            var active=adapter;
            bool resetNeeded=false;
            try {
                string result=await Task.Run(delegate { return active.Send(desired?"SKILLFX 1":"SKILLFX 0"); });
                if(requestEpoch!=epoch||adapter!=active) return;
                if(result!=(desired?"OK SKILLFX=1":"OK SKILLFX=0")) throw new IOException(result);
                Append(desired?"Local skill-effect suppression acknowledged; in-game verification pending.":"Local skill effects restored.");
            } catch(Exception error) {
                if(requestEpoch!=epoch||adapter!=active) return;
                Append("Skill effects: "+error.Message); resetNeeded=true;
            } finally {
                skillEffectsBusy=false;
                skillEffects.AutoCheck=paired&&adapter!=null&&adapter.SkillEffectsReady&&!panicBusy&&!resetNeeded;
            }
            if(resetNeeded) await Off();
        }
        private async Task SetFallThrough() {
            if(syncing||!paired||panicBusy||adapter==null||fallThroughBusy) return;
            if(fallThrough.Checked&&(fly.Checked||hover.Checked)) { syncing=true; fallThrough.Checked=false; syncing=false; Append("Turn Fly and Hover off before enabling Fall Through."); return; }
            fallThroughBusy=true; fallThrough.AutoCheck=false;
            bool desired=fallThrough.Checked; int requestEpoch=epoch; var active=adapter; bool resetNeeded=false;
            try {
                string result=await Task.Run(delegate { return active.Send(desired?"FALLTHROUGH 1":"FALLTHROUGH 0"); });
                if(requestEpoch!=epoch||adapter!=active) return;
                if(result!=(desired?"OK FALLTHROUGH=1":"OK FALLTHROUGH=0")) throw new IOException(result);
                Append(desired?"Fall Through candidate armed. Use Down + Jump; live lower-platform/bounds verification pending.":"Normal platform fall behavior restored.");
            } catch(Exception error) { if(requestEpoch==epoch&&adapter==active) { Append("Fall Through: "+error.Message); resetNeeded=true; } }
            finally { fallThroughBusy=false; fallThrough.AutoCheck=paired&&adapter!=null&&adapter.FallThroughReady&&!panicBusy&&!resetNeeded; }
            if(resetNeeded) await Off();
        }
        private async Task SetExtraNative(bool cpu) {
            if(syncing||!paired||panicBusy||adapter==null||extraNativeBusy) return;
            var control=cpu?cpuMode:hover; bool desired=control.Checked;
            if(!cpu&&desired&&(fly.Checked||fallThrough.Checked)) {
                syncing=true; hover.Checked=false; syncing=false; Append("Turn Fly and Fall Through off before Hover."); return;
            }
            extraNativeBusy=true; SetControls(false);
            int requestEpoch=epoch; var active=adapter; bool resetNeeded=false;
            string key=cpu?"CPU":"HOVER";
            string command=key+" "+(desired?"1":"0")+(cpu?" "+backgroundFps.Value.ToString():"");
            try {
                string result=await Task.Run(delegate { return active.Send(command); });
                if(requestEpoch!=epoch||adapter!=active) return;
                if(result!="OK "+key+"="+(desired?"1":"0")) throw new IOException(result);
                Append(key+" "+(desired?"acknowledged; candidate gameplay verification pending.":"OFF"));
            } catch(Exception error) { if(requestEpoch==epoch&&adapter==active) { Append(key+": "+error.Message); resetNeeded=true; } }
            finally { extraNativeBusy=false; if(requestEpoch==epoch) SetControls(paired&&!resetNeeded); }
            if(resetNeeded) await Off();
        }
        private async Task SetRapid() {
            if(syncing||!paired||panicBusy||adapter==null||rapidBusy) return;
            if(busy) { syncing=true; rapid.Checked=!rapid.Checked; syncing=false; return; }
            rapidBusy=true; rapid.AutoCheck=false;
            bool desired=rapid.Checked;
            int requestEpoch=epoch;
            var active=adapter;
            bool resetNeeded=false;
            try {
                status.Text="Rapid Attack pending native and server confirmation";
                string result=await Task.Run(delegate { return active.Send(desired?"RAPID 1":"RAPID 0"); });
                if(requestEpoch!=epoch||adapter!=active) return;
                if(result!=(desired?"OK RAPID=1":"OK RAPID=0")) throw new IOException(result);
                await Configure();
                if(requestEpoch==epoch&&paired) Append(desired
                    ? "Rapid Attack candidate armed. Confirm real accepted swing cadence and damage in game."
                    : "Rapid Attack off; normal local recovery restored.");
            } catch(Exception error) {
                if(requestEpoch!=epoch||adapter!=active) return;
                Append("Rapid Attack: "+error.Message);
                resetNeeded=true;
            } finally {
                rapidBusy=false;
                rapid.AutoCheck=paired&&adapter!=null&&adapter.RapidReady&&!panicBusy&&!resetNeeded;
            }
            if(resetNeeded) await Off();
        }
        private async Task Off() {
            if(panicBusy) return;
            panicBusy=true; epoch++; paired=false; autoPotionDraft=false; autoPotionReady=false; powerPanel.Reset(); lootToolsPanel.Reset(); mobToolsPanel.Reset(); pickupPanel.Reset(); regenPanel.Reset(); connect.Enabled=false;
            var a=adapter; adapter=null;
            if(a!=null) {
                string clientReset=await Task.Run(delegate { return a.CloseWithReset(); });
                if(clientReset!="OK OFF") Append("Client reset needs verification: "+clientReset);
            }
            var b=bridge; syncing=true; vac.Checked=itemVac.Checked=mesoVac.Checked=lootOnKey.Checked=fma.Checked=fmaOneHit.Checked=hpGod.Checked=hpRegen.Checked=mpRegen.Checked=fly.Checked=unlimited.Checked=rapid.Checked=skillEffects.Checked=fallThrough.Checked=hover.Checked=cpuMode.Checked=autoHp.Checked=autoMp.Checked=false; fmaDamage.Value=1; syncing=false; pendingConfiguration=false; filtersInitialized=false; SetControls(false); status.Text="PANIC pending / lease fallback <=5 seconds";
            if(b==null||b.Token==null) { status.Text="OFFLINE - no powers"; panicBusy=false; connect.Enabled=true; return; }
            try { var result=await Task.Run(delegate { return b.Send("off",new Dictionary<string,string>()); }); if(!closing) { UpdateState(result); paired=false; filtersInitialized=false; b.Token=null; Append("Server confirmed all OFF. Click INJECT HAX to resume."); SetControls(false); } }
            catch(Exception e) { Append(e.ToString()); Disconnect(e.Message); }
            finally { panicBusy=false; if(!closing) connect.Enabled=true; }
        }
        private void UpdateState(Dictionary<string,string> r) {
            profileReady=r.ContainsKey("profileReady")&&r["profileReady"]=="1";
            if(currentMap!=r["map"]) { inspection=null; inspectorOffset=0; inspectorRows.Rows.Clear(); inspectorCanvas.Invalidate(); }
            currentMap=r["map"];
            regenPanel.State(r,"regenOptionsReady",new Dictionary<string,string>{{"interval","regenInterval"}});
            pickupPanel.State(r,"pickupOptionsReady",new Dictionary<string,string>{{"interval","pickupInterval"},{"batch","pickupBatch"}});
            if(r.ContainsKey("statsReady")&&r["statsReady"]=="1") sessionStats.Text="Runtime: "+TimeSpan.FromMilliseconds(Double.Parse(r["sessionMs"])).ToString(@"hh\:mm\:ss")
                +"\r\nFinishing kills: "+r["finishingKills"]+" (your finishing hit; excludes party-only kills)"
                +"\r\nNet EXP: "+r["netExp"]+" / "+r["expPerHour"]+" per hour"
                +"\r\nEXP includes party/quest rewards and subtracts losses."
                +"\r\nNet meso balance: "+r["netMesos"]+" (includes spending)"
                +"\r\nTrainer loot: "+r["acquiredItems"]+" item units / "+r["acquiredMesos"]+" mesos"
                +"\r\nRejected trainer pickups: "+r["rejectedPickups"]
                +"\r\nAccepted melee/ranged/magic swings/min: "+r["swingsPerMinute"];

            powerPanel.State(r,"powerOptionsReady",new Dictionary<string,string>{{"roll","damageRoll"}});
            mobToolsPanel.State(r,"mobToolsReady",new Dictionary<string,string>{{"freeze","mobFreeze"},{"disarm","mobDisarm"},{"aggro","mobAggro"},{"radius","mobRadius"},{"include","mobInclude"},{"exclude","mobExclude"},{"x","pointX"},{"y","pointY"},{"spacing","mobSpacing"},{"pullStep","mobPullStep"}});
            lootToolsPanel.State(r,"lootToolsReady",new Dictionary<string,string>{{"name","lootName"},{"category","lootCategory"},{"minValue","lootMinValue"},{"source","lootSource"}});
            autoPotionReady=r.ContainsKey("autoPotionReady")&&r["autoPotionReady"]=="1";
            if(autoPotionReady) {
                string[] fields={"autoHp","autoMp","autoHpThreshold","autoMpThreshold","autoHpItem","autoMpItem","autoReserve","autoInterval","autoConsumed","autoDetail"};
                foreach(string field in fields) if(!r.ContainsKey(field)) throw new IOException("Incomplete auto potion status.");
                if(!autoPotionDraft) { syncing=true; try {
                    autoHp.Checked=r["autoHp"]=="1"; autoMp.Checked=r["autoMp"]=="1";
                    autoHpThreshold.Value=Int32.Parse(r["autoHpThreshold"]); autoMpThreshold.Value=Int32.Parse(r["autoMpThreshold"]); autoReserve.Value=Int32.Parse(r["autoReserve"]); autoInterval.Value=Int32.Parse(r["autoInterval"]);
                    foreach(var box in new[]{autoHpItem,autoMpItem}) { string id=r[box==autoHpItem?"autoHpItem":"autoMpItem"]; int found=-1; for(int i=0;i<box.Items.Count;i++) if(box.Items[i].ToString().StartsWith(id+" ")) found=i; if(found<0) throw new IOException("Unknown potion selection"); box.SelectedIndex=found; }
                } finally { syncing=false; } }
                autoPotionStatus.Text="HP "+(r["autoHp"]=="1"?"ON":"OFF")+" / MP "+(r["autoMp"]=="1"?"ON":"OFF")+" / used "+r["autoConsumed"]+"\r\n"+r["autoDetail"];
            }
            string[] required={"vac","vacMode","itemVac","mesoVac","lootOnKey","lootRadius","lootBatch","lootOrder","includeIds","excludeIds","minMeso","maxMeso","fma","fmaDamage","fmaOneHit","rapid","hpGod","hpRegen","mpRegen","interval","character","map","moved","looted","hits","pulses","fmaCasts","lastFmaTargets","lastAttackSkill","lastFmaReason","detail"};
            foreach(string k in required) if(!r.ContainsKey(k)) throw new IOException("Incomplete status; refuse active display.");
            if(!pendingConfiguration) { syncing=true; try {
                vac.Checked=r["vac"]=="1"; itemVac.Checked=r["itemVac"]=="1"; mesoVac.Checked=r["mesoVac"]=="1"; lootOnKey.Checked=r["lootOnKey"]=="1"; fma.Checked=r["fma"]=="1"; fmaOneHit.Checked=r["fmaOneHit"]=="1"; rapid.Checked=r["rapid"]=="1"; hpGod.Checked=r["hpGod"]=="1"; hpRegen.Checked=r["hpRegen"]=="1"; mpRegen.Checked=r["mpRegen"]=="1";
                int cadence; if(!Int32.TryParse(r["interval"],out cadence)||cadence<150||cadence>1000) throw new IOException("Invalid rapid interval"); attackInterval.Value=cadence;
                int value; if(!Int32.TryParse(r["fmaDamage"],out value)||value<1||value>100) throw new IOException("Invalid FMA multiplier"); fmaDamage.Value=value;
                if(!vacMode.Items.Contains(r["vacMode"])) throw new IOException("Invalid Mob Vac mode"); vacMode.SelectedItem=r["vacMode"];
                if(!Int32.TryParse(r["lootRadius"],out value)||value<0||value>2000) throw new IOException("Invalid loot radius"); lootRadius.Value=value;
                if(!Int32.TryParse(r["lootBatch"],out value)||value<1||value>25) throw new IOException("Invalid loot batch"); lootBatch.Value=value;
            } finally { syncing=false; } }
            if(!filtersInitialized) { syncing=true; try {
                includeIds.Text=r["includeIds"]; excludeIds.Text=r["excludeIds"]; lootOrder.SelectedItem=r["lootOrder"];
                int value; if(!Int32.TryParse(r["minMeso"],out value)||value<0) throw new IOException("Invalid meso filter"); minMeso.Value=value;
                if(!Int32.TryParse(r["maxMeso"],out value)||value<0) throw new IOException("Invalid meso filter"); maxMeso.Value=value;
                filtersInitialized=true;
            } finally { syncing=false; } }
            counters.Text="Mobs: "+r["moved"]+"   Drops: "+r["looted"]+"   Extra hits: "+r["hits"]+"\r\nSwings: "+r["pulses"]+"   FMA casts: "+r["fmaCasts"]+"   Last extra targets: "+r["lastFmaTargets"];
            long casts; if(!Int64.TryParse(r["fmaCasts"],out casts)||casts<0) throw new IOException("Invalid FMA cast count");
            if(casts<lastLoggedFmaCast) lastLoggedFmaCast=0;
            if(casts>lastLoggedFmaCast) { lastLoggedFmaCast=casts; Append("FMA cast "+casts+": "+r["lastFmaTargets"]+" extra targets, skill "+r["lastAttackSkill"]+". "+r["lastFmaReason"]); }
            characterName=r["character"];
            identity.Text="GAME SESSION / "+r["character"]+"  |  OWN SERVER: paired  |  MAP "+r["map"]+"  |  FLY "+(adapter!=null&&adapter.FlyReady?"READY":"OFF");
            status.Text=r["detail"]; status.ForeColor=(vac.Checked||itemVac.Checked||mesoVac.Checked||fma.Checked||rapid.Checked||hpGod.Checked||hpRegen.Checked||mpRegen.Checked)?Color.Lime:Color.Orange;
        }
        private void Disconnect(string reason) {
            lastNativeMap=null; paired=false; autoPotionDraft=false; autoPotionReady=false; powerPanel.Reset(); lootToolsPanel.Reset(); mobToolsPanel.Reset(); pickupPanel.Reset(); regenPanel.Reset(); if(bridge!=null) bridge.Token=null; if(adapter!=null) { var old=adapter; adapter=null; Task.Run(delegate { old.Dispose(); }); } SetControls(false); syncing=true; vac.Checked=itemVac.Checked=mesoVac.Checked=lootOnKey.Checked=fma.Checked=fmaOneHit.Checked=hpGod.Checked=hpRegen.Checked=mpRegen.Checked=fly.Checked=unlimited.Checked=rapid.Checked=skillEffects.Checked=fallThrough.Checked=hover.Checked=cpuMode.Checked=autoHp.Checked=autoMp.Checked=false; fmaDamage.Value=1; syncing=false; pendingConfiguration=false; filtersInitialized=false;
            if(closing) return; identity.Text="GAME unchanged  |  SERVER: DISCONNECTED  |  effects expire <=5 sec"; status.Text=reason; status.ForeColor=Color.OrangeRed; Append(reason);
        }
    }
    internal static class Program {
        [STAThread] private static int Main(string[] args) {
            ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;
            if(args.Length>0&&args[0]=="--self-test") {
                var source=new Dictionary<string,string>{{"detail","a b + % & ="},{"vac","1"}};
                if(Wire.Decode(Wire.Encode(source))["detail"]!=source["detail"]) return 1;
                try { Wire.Decode("vac=0&vac=1"); return 2; } catch(InvalidDataException) {}
                var shortcut=new FlyShortcut();
                if(!shortcut.Pressed(true,true)||shortcut.Pressed(true,true)) return 3;
                if(shortcut.Pressed(false,true)||!shortcut.Pressed(true,true)) return 4;
                shortcut.Reset();
                if(shortcut.Pressed(true,false)||shortcut.Pressed(true,true)) return 5;
                if(shortcut.Pressed(false,true)||!shortcut.Pressed(true,true)) return 6;
                PresetFile.SelfTest();
                Console.WriteLine("PASS codec, fly shortcut press/release/focus gating, preset roundtrip/validation/atomic replacement"); return 0;
            }
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
            if(args.Length>=2&&args.Length<=3&&args[0]=="--render-check") {
                using(var form=new TrainerWindow()) { form.ShowInTaskbar=false; form.StartPosition=FormStartPosition.Manual; form.Location=new Point(-32000,-32000); form.Show(); Application.DoEvents();
                    if(args.Length==3) foreach(Control control in form.Controls) { var tabs=control as TabControl; if(tabs!=null) tabs.SelectedIndex=Int32.Parse(args[2]); }
                    Application.DoEvents(); using(var bitmap=new Bitmap(form.Width,form.Height)) { form.DrawToBitmap(bitmap,new Rectangle(0,0,form.Width,form.Height)); bitmap.Save(args[1],System.Drawing.Imaging.ImageFormat.Png); } form.Close(); } return 0;
            }
            Application.Run(new TrainerWindow()); return 0;
        }
    }
}
