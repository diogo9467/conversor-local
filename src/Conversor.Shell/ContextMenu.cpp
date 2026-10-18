// Native, fast IExplorerCommand implementation; no conversion runs in Explorer.
#define UNICODE
#define _UNICODE
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <shobjidl.h>
#include <shlwapi.h>
#include <shlobj.h>
#include <string>
#include <vector>
#include <atomic>
#include <new>
#include <algorithm>

static const CLSID CLSID_Conversor = {0x43cf6a2e,0x7404,0x4f6b,{0x9d,0x87,0xc8,0x69,0xf5,0xa1,0x1c,0x70}};
static HMODULE moduleHandle;
static std::atomic<long> objects{0};

static HRESULT copyString(const wchar_t* text, PWSTR* result) { return SHStrDupW(text,result); }

static bool selectedFiles(IShellItemArray* items,std::vector<std::wstring>& paths) {
    if(!items) return false;
    DWORD count=0; if(FAILED(items->GetCount(&count)) || count==0 || count>10000) return false;
    for(DWORD i=0;i<count;++i) {
        IShellItem* item=nullptr; PWSTR path=nullptr;
        if(FAILED(items->GetItemAt(i,&item))) return false;
        HRESULT hr=item->GetDisplayName(SIGDN_FILESYSPATH,&path); item->Release();
        if(FAILED(hr) || !path) return false;
        paths.emplace_back(path); CoTaskMemFree(path);
    }
    return !paths.empty();
}
static bool allowed(const std::vector<std::wstring>& files,int target) {
    if(files.empty()) return false;
    for(auto& file:files) {
        auto ext=PathFindExtensionW(file.c_str());
        if(target==1) { if(_wcsicmp(ext,L".docx") && _wcsicmp(ext,L".pptx")) return false; }
        else if(_wcsicmp(ext,L".pdf")) return false;
    }
    return true;
}
static std::wstring quote(const std::wstring& value) {
    std::wstring result=L"\""; size_t slashes=0;
    for(auto ch:value) {
        if(ch==L'\\') { ++slashes; continue; }
        if(ch==L'\"') { result.append(slashes*2+1,L'\\'); result+=ch; }
        else { result.append(slashes,L'\\'); result+=ch; }
        slashes=0;
    }
    result.append(slashes*2,L'\\'); result+=L'\"'; return result;
}
static std::string utf8(const std::wstring& value) {
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),(int)value.size(),nullptr,0,nullptr,nullptr);
    std::string result(n,'\0');
    WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),(int)value.size(),result.data(),n,nullptr,nullptr);
    return result;
}
static std::string jsonString(const std::wstring& value) {
    std::string result="\"";
    for(unsigned char c:utf8(value)) {
        if(c=='\\' || c=='"') { result+='\\'; result+=c; }
        else if(c<32) { char escape[7]; wsprintfA(escape,"\\u%04x",c); result+=escape; }
        else result+=c;
    }
    return result+'"';
}
static HRESULT launch(IShellItemArray* items,int target) {
    std::vector<std::wstring> files;
    if(!selectedFiles(items,files) || !allowed(files,target)) return E_INVALIDARG;
    std::vector<wchar_t> buffer(32768);
    DWORD size=GetModuleFileNameW(moduleHandle,buffer.data(),(DWORD)buffer.size());
    if(size==0 || size==buffer.size()) return E_FAIL;
    std::wstring executable(buffer.data(),size);
    executable=executable.substr(0,executable.find_last_of(L"\\/"))+L"\\ConversorLocal.exe";
    PWSTR local=nullptr;
    if(FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData,0,nullptr,&local))) return E_FAIL;
    std::wstring directory=std::wstring(local)+L"\\ConversorLocal\\queue"; CoTaskMemFree(local);
    if(SHCreateDirectoryExW(nullptr,directory.c_str(),nullptr)!=ERROR_SUCCESS && GetLastError()==ERROR_ACCESS_DENIED) return E_ACCESSDENIED;
    GUID id; if(FAILED(CoCreateGuid(&id))) return E_FAIL;
    wchar_t guid[40]; StringFromGUID2(id,guid,40);
    auto path=directory+L"\\"+guid+L".json";
    HANDLE file=CreateFileW(path.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_TEMPORARY,nullptr);
    if(file==INVALID_HANDLE_VALUE) return HRESULT_FROM_WIN32(GetLastError());
    const char* format=target==1?"pdf":target==2?"docx":"pptx";
    std::string data=std::string("{\"target\":\"")+format+"\",\"files\":[";
    for(size_t i=0;i<files.size();++i) { if(i) data+=','; data+=jsonString(files[i]); }
    data+="]}";
    DWORD written=0; bool ok=WriteFile(file,data.data(),(DWORD)data.size(),&written,nullptr) && written==data.size();
    CloseHandle(file);
    if(!ok) { DeleteFileW(path.c_str()); return E_FAIL; }
    std::wstring command=quote(executable)+L" --batch "+quote(path);
    STARTUPINFOW startup{sizeof(startup)}; PROCESS_INFORMATION process{};
    if(!CreateProcessW(executable.c_str(),command.data(),nullptr,nullptr,FALSE,CREATE_UNICODE_ENVIRONMENT,nullptr,nullptr,&startup,&process)) {
        auto error=GetLastError(); DeleteFileW(path.c_str()); return HRESULT_FROM_WIN32(error);
    }
    CloseHandle(process.hThread); CloseHandle(process.hProcess); return S_OK;
}

class Command;
class Enumerator final: public IEnumExplorerCommand {
    std::atomic<ULONG> refs{1}; ULONG index=0;
public:
    Enumerator() {++objects;} ~Enumerator(){--objects;}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid,void** out) override {
        if(!out)return E_POINTER; *out=nullptr;
        if(IsEqualIID(riid,IID_IUnknown)||IsEqualIID(riid,IID_IEnumExplorerCommand))*out=static_cast<IEnumExplorerCommand*>(this);
        if(*out){AddRef();return S_OK;} return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE Next(ULONG count,IExplorerCommand** values,ULONG* fetched) override;
    HRESULT STDMETHODCALLTYPE Skip(ULONG count) override{ULONG old=index;index=std::min(3ul,index+std::min(count,3ul));return index-old==count?S_OK:S_FALSE;}
    HRESULT STDMETHODCALLTYPE Reset() override{index=0;return S_OK;}
    HRESULT STDMETHODCALLTYPE Clone(IEnumExplorerCommand** result) override {
        if(!result)return E_POINTER;auto e=new(std::nothrow) Enumerator();if(!e)return E_OUTOFMEMORY;e->index=index;*result=e;return S_OK;
    }
};

class Command final: public IExplorerCommand {
    std::atomic<ULONG> refs{1}; int target;
public:
    explicit Command(int target=0):target(target){++objects;} ~Command(){--objects;}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid,void** out) override {
        if(!out)return E_POINTER;*out=nullptr;
        if(IsEqualIID(riid,IID_IUnknown)||IsEqualIID(riid,IID_IExplorerCommand))*out=static_cast<IExplorerCommand*>(this);
        if(*out){AddRef();return S_OK;} return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE GetTitle(IShellItemArray*,PWSTR* text) override {
        return copyString(target==0?L"Converter arquivo":target==1?L"Converter para PDF":target==2?L"Converter para DOCX":L"Converter para PPTX",text);
    }
    HRESULT STDMETHODCALLTYPE GetIcon(IShellItemArray*,PWSTR* icon) override {
        if(!icon)return E_POINTER;*icon=nullptr;wchar_t path[32768];GetModuleFileNameW(moduleHandle,path,32768);
        std::wstring result(path);result=result.substr(0,result.find_last_of(L"\\/"))+L"\\ConversorLocal.exe,0";return copyString(result.c_str(),icon);
    }
    HRESULT STDMETHODCALLTYPE GetToolTip(IShellItemArray*,PWSTR* text) override{return copyString(L"Converte offline e salva na mesma pasta, preservando o original.",text);}
    HRESULT STDMETHODCALLTYPE GetCanonicalName(GUID* id) override{if(!id)return E_POINTER;*id=CLSID_Conversor;id->Data1+=target;return S_OK;}
    HRESULT STDMETHODCALLTYPE GetState(IShellItemArray* items,BOOL,EXPCMDSTATE* state) override {
        if(!state)return E_POINTER;*state=ECS_HIDDEN;
        std::vector<std::wstring> files;
        if(selectedFiles(items,files) && (target?allowed(files,target):(allowed(files,1)||allowed(files,2))))*state=ECS_ENABLED;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Invoke(IShellItemArray* items,IBindCtx*) override{if(!target)return E_NOTIMPL;return launch(items,target);}
    HRESULT STDMETHODCALLTYPE GetFlags(EXPCMDFLAGS* flags) override{if(!flags)return E_POINTER;*flags=target?ECF_DEFAULT:ECF_HASSUBCOMMANDS;return S_OK;}
    HRESULT STDMETHODCALLTYPE EnumSubCommands(IEnumExplorerCommand** result) override {
        if(!result)return E_POINTER;*result=nullptr;if(target)return E_NOTIMPL;*result=new(std::nothrow) Enumerator();return *result?S_OK:E_OUTOFMEMORY;
    }
};
HRESULT Enumerator::Next(ULONG count,IExplorerCommand** values,ULONG* fetched) {
    if(!values || (!fetched && count!=1))return E_POINTER;
    ULONG n=0;
    while(n<count && index<3){values[n]=new(std::nothrow) Command((int)index+1);if(!values[n]){if(fetched)*fetched=n;return E_OUTOFMEMORY;}++n;++index;}
    if(fetched)*fetched=n;return n==count?S_OK:S_FALSE;
}
class Factory final:public IClassFactory {
    std::atomic<ULONG> refs{1};
public:
    Factory(){++objects;}~Factory(){--objects;}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out) override{
        if(!out)return E_POINTER;*out=nullptr;if(IsEqualIID(id,IID_IUnknown)||IsEqualIID(id,IID_IClassFactory))*out=static_cast<IClassFactory*>(this);
        if(*out){AddRef();return S_OK;}return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{auto n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* outer,REFIID id,void** result) override{
        if(outer)return CLASS_E_NOAGGREGATION;auto cmd=new(std::nothrow) Command();if(!cmd)return E_OUTOFMEMORY;
        auto hr=cmd->QueryInterface(id,result);cmd->Release();return hr;
    }
    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override{objects+=lock?1:-1;return S_OK;}
};
extern "C" __declspec(dllexport) HRESULT __stdcall DllGetClassObject(REFCLSID clsid,REFIID riid,void** out){
    if(!IsEqualCLSID(clsid,CLSID_Conversor))return CLASS_E_CLASSNOTAVAILABLE;
    auto factory=new(std::nothrow) Factory();if(!factory)return E_OUTOFMEMORY;auto hr=factory->QueryInterface(riid,out);factory->Release();return hr;
}
extern "C" __declspec(dllexport) HRESULT __stdcall DllCanUnloadNow(){return objects==0?S_OK:S_FALSE;}
BOOL WINAPI DllMain(HINSTANCE module,DWORD reason,LPVOID){if(reason==DLL_PROCESS_ATTACH){moduleHandle=module;DisableThreadLibraryCalls(module);}return TRUE;}
