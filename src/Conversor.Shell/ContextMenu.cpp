// Native, fast IExplorerCommand implementation; no conversion runs in Explorer.
#define UNICODE
#define _UNICODE
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <shobjidl.h>
#include <shlwapi.h>
#include <shlobj.h>
#include <shellapi.h>
#include <string>
#include <vector>
#include <atomic>
#include <new>
#include <algorithm>
#include "FormatCatalog.h"

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
    if(files.empty() || target<1 || target>(int)TargetCount) return false;
    for(auto& file:files) {
        auto ext=PathFindExtensionW(file.c_str());
        bool supported=false;
        for(const auto& source:ConversionSources) {
            if(!_wcsicmp(ext,source.extension)) { supported=(source.mask & (1u<<(target-1)))!=0; break; }
        }
        if(!supported)return false;
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
static HRESULT launchFiles(const std::vector<std::wstring>& files,int target) {
    if(!allowed(files,target)) return E_INVALIDARG;
    std::vector<wchar_t> buffer(32768);
    DWORD size=GetModuleFileNameW(moduleHandle,buffer.data(),(DWORD)buffer.size());
    if(size==0 || size==buffer.size()) return E_FAIL;
    std::wstring executable(buffer.data(),size);
    executable=executable.substr(0,executable.find_last_of(L"\\/"))+L"\\ConversorLocal.exe";
    PWSTR local=nullptr;
    if(FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData,0,nullptr,&local))) return E_FAIL;
    std::wstring directory=std::wstring(local)+L"\\ConversorLocal\\queue"; CoTaskMemFree(local);
    auto directoryStatus=SHCreateDirectoryExW(nullptr,directory.c_str(),nullptr);
    if(directoryStatus!=ERROR_SUCCESS && directoryStatus!=ERROR_ALREADY_EXISTS && directoryStatus!=ERROR_FILE_EXISTS)return HRESULT_FROM_WIN32(directoryStatus);
    GUID id; if(FAILED(CoCreateGuid(&id))) return E_FAIL;
    wchar_t guid[40]; StringFromGUID2(id,guid,40);
    auto path=directory+L"\\"+guid+L".json";
    HANDLE file=CreateFileW(path.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_TEMPORARY,nullptr);
    if(file==INVALID_HANDLE_VALUE) return HRESULT_FROM_WIN32(GetLastError());
    const char* format=ConversionTargets[target-1].id;
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
static HRESULT launch(IShellItemArray* items,int target) {
    std::vector<std::wstring> files;
    return selectedFiles(items,files)?launchFiles(files,target):E_INVALIDARG;
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
    HRESULT STDMETHODCALLTYPE Skip(ULONG count) override{ULONG old=index;index=std::min(TargetCount,index+std::min(count,TargetCount));return index-old==count?S_OK:S_FALSE;}
    HRESULT STDMETHODCALLTYPE Reset() override{index=0;return S_OK;}
    HRESULT STDMETHODCALLTYPE Clone(IEnumExplorerCommand** result) override {
        if(!result)return E_POINTER;auto e=new(std::nothrow) Enumerator();if(!e)return E_OUTOFMEMORY;e->index=index;*result=e;return S_OK;
    }
};

class Command final: public IExplorerCommand, public IShellExtInit, public IContextMenu {
    std::atomic<ULONG> refs{1}; int target;
    std::vector<std::wstring> legacyFiles;
public:
    explicit Command(int target=0):target(target){++objects;} ~Command(){--objects;}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid,void** out) override {
        if(!out)return E_POINTER;*out=nullptr;
        if(IsEqualIID(riid,IID_IUnknown)||IsEqualIID(riid,IID_IExplorerCommand))*out=static_cast<IExplorerCommand*>(this);
        else if(!target && IsEqualIID(riid,IID_IShellExtInit))*out=static_cast<IShellExtInit*>(this);
        else if(!target && IsEqualIID(riid,IID_IContextMenu))*out=static_cast<IContextMenu*>(this);
        if(*out){AddRef();return S_OK;} return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
    ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE GetTitle(IShellItemArray*,PWSTR* text) override {
        return copyString(target==0?L"Converter arquivo":ConversionTargets[target-1].title,text);
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
        if(selectedFiles(items,files)) {
            if(target) { if(allowed(files,target))*state=ECS_ENABLED; }
            else for(unsigned long candidate=1;candidate<=TargetCount;++candidate)if(allowed(files,(int)candidate)){*state=ECS_ENABLED;break;}
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Invoke(IShellItemArray* items,IBindCtx*) override{if(!target)return E_NOTIMPL;return launch(items,target);}
    HRESULT STDMETHODCALLTYPE GetFlags(EXPCMDFLAGS* flags) override{if(!flags)return E_POINTER;*flags=target?ECF_DEFAULT:ECF_HASSUBCOMMANDS;return S_OK;}
    HRESULT STDMETHODCALLTYPE EnumSubCommands(IEnumExplorerCommand** result) override {
        if(!result)return E_POINTER;*result=nullptr;if(target)return E_NOTIMPL;*result=new(std::nothrow) Enumerator();return *result?S_OK:E_OUTOFMEMORY;
    }
    HRESULT STDMETHODCALLTYPE Initialize(PCIDLIST_ABSOLUTE,IDataObject* data,HKEY) override {
        legacyFiles.clear();if(!data)return E_INVALIDARG;
        FORMATETC format{CF_HDROP,nullptr,DVASPECT_CONTENT,-1,TYMED_HGLOBAL};STGMEDIUM medium{};
        auto hr=data->GetData(&format,&medium);if(FAILED(hr))return hr;
        auto drop=(HDROP)medium.hGlobal;UINT count=DragQueryFileW(drop,0xffffffff,nullptr,0);
        if(count==0 || count>10000){ReleaseStgMedium(&medium);return E_INVALIDARG;}
        bool valid=true;
        for(UINT i=0;i<count;++i) {
            UINT length=DragQueryFileW(drop,i,nullptr,0);
            if(!length || length>32767){valid=false;break;}
            std::wstring path(length+1,L'\0');
            if(DragQueryFileW(drop,i,path.data(),length+1)!=length){valid=false;break;}
            path.resize(length);legacyFiles.push_back(std::move(path));
        }
        ReleaseStgMedium(&medium);
        if(!valid){legacyFiles.clear();return E_INVALIDARG;}return S_OK;
    }
    HRESULT STDMETHODCALLTYPE QueryContextMenu(HMENU menu,UINT position,UINT first,UINT last,UINT flags) override {
        if((flags&CMF_DEFAULTONLY) || legacyFiles.empty() || last<first || last-first+1<TargetCount)return MAKE_HRESULT(SEVERITY_SUCCESS,0,0);
        HMENU submenu=CreatePopupMenu();if(!submenu)return E_OUTOFMEMORY;
        UINT added=0;
        for(unsigned long i=0;i<TargetCount;++i)if(allowed(legacyFiles,(int)i+1)) {
            if(!AppendMenuW(submenu,MF_STRING,first+(UINT)i,ConversionTargets[i].title)){DestroyMenu(submenu);return E_FAIL;}
            ++added;
        }
        if(!added){DestroyMenu(submenu);return MAKE_HRESULT(SEVERITY_SUCCESS,0,0);}
        if(!InsertMenuW(menu,position,MF_BYPOSITION|MF_POPUP,(UINT_PTR)submenu,L"Converter arquivo")){DestroyMenu(submenu);return E_FAIL;}
        return MAKE_HRESULT(SEVERITY_SUCCESS,0,TargetCount);
    }
    HRESULT STDMETHODCALLTYPE InvokeCommand(CMINVOKECOMMANDINFO* command) override {
        if(!command || HIWORD(command->lpVerb) || LOWORD(command->lpVerb)>=TargetCount)return E_INVALIDARG;
        return launchFiles(legacyFiles,(int)LOWORD(command->lpVerb)+1);
    }
    HRESULT STDMETHODCALLTYPE GetCommandString(UINT_PTR id,UINT type,UINT*,LPSTR text,UINT count) override {
        if(id>=TargetCount)return E_INVALIDARG;
        if(type==GCS_VALIDATEA || type==GCS_VALIDATEW)return S_OK;
        if(!text || !count)return E_POINTER;
        if(type==GCS_HELPTEXTW){lstrcpynW((PWSTR)text,L"Converte offline e salva na pasta do original.",(int)count);return S_OK;}
        if(type==GCS_HELPTEXTA){lstrcpynA(text,"Converte offline e salva na pasta do original.",(int)count);return S_OK;}
        return E_NOTIMPL;
    }
};
HRESULT Enumerator::Next(ULONG count,IExplorerCommand** values,ULONG* fetched) {
    if(!values || (!fetched && count!=1))return E_POINTER;
    ULONG n=0;
    while(n<count && index<TargetCount){values[n]=new(std::nothrow) Command((int)index+1);if(!values[n]){if(fetched)*fetched=n;return E_OUTOFMEMORY;}++n;++index;}
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
