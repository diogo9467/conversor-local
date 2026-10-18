#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <shobjidl.h>
#include <shlobj.h>
#include <cstdio>
#include <cwctype>
#include <string>
#include <vector>
#include <set>
#include "FormatCatalog.h"

static IShellItemArray* selection(const std::vector<std::wstring>& paths) {
    std::vector<PIDLIST_ABSOLUTE> pidls;
    for(const auto& path:paths) {
        PIDLIST_ABSOLUTE pidl=nullptr;
        if(FAILED(SHParseDisplayName(path.c_str(),nullptr,&pidl,0,nullptr)))return nullptr;
        pidls.push_back(pidl);
    }
    IShellItemArray* items=nullptr;
    SHCreateShellItemArrayFromIDLists(static_cast<UINT>(pidls.size()),const_cast<PCIDLIST_ABSOLUTE*>(pidls.data()),&items);
    for(auto pidl:pidls)CoTaskMemFree(pidl);
    return items;
}

static IDataObject* selectionData(const std::wstring& root,const std::vector<std::wstring>& paths) {
    PIDLIST_ABSOLUTE parent=nullptr;
    if(FAILED(SHParseDisplayName(root.c_str(),nullptr,&parent,0,nullptr)))return nullptr;
    std::vector<PIDLIST_ABSOLUTE> full;std::vector<PCUITEMID_CHILD> children;
    for(const auto& path:paths) {
        PIDLIST_ABSOLUTE pidl=nullptr;
        if(FAILED(SHParseDisplayName(path.c_str(),nullptr,&pidl,0,nullptr)))return nullptr;
        full.push_back(pidl);children.push_back(ILFindLastID(pidl));
    }
    IDataObject* data=nullptr;
    SHCreateDataObject(parent,(UINT)children.size(),children.data(),nullptr,IID_IDataObject,(void**)&data);
    for(auto pidl:full)CoTaskMemFree(pidl);CoTaskMemFree(parent);return data;
}

int wmain(int argc,wchar_t** argv) {
    if(argc!=3)return 2;
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    auto dll=LoadLibraryW(argv[1]);if(!dll)return 3;
    auto getClass=(HRESULT(WINAPI*)(REFCLSID,REFIID,void**))GetProcAddress(dll,"DllGetClassObject");
    auto canUnload=(HRESULT(WINAPI*)())GetProcAddress(dll,"DllCanUnloadNow");
    if(!getClass || !canUnload)return 4;
    IClassFactory* factory=nullptr;
    if(FAILED(getClass(CLSID_Conversor,IID_IClassFactory,(void**)&factory)))return 5;
    IExplorerCommand* command=nullptr;
    if(FAILED(factory->CreateInstance(nullptr,IID_IExplorerCommand,(void**)&command)))return 6;
    IShellExtInit* initializer=nullptr;IContextMenu* context=nullptr;
    if(FAILED(command->QueryInterface(IID_IShellExtInit,(void**)&initializer)) || FAILED(command->QueryInterface(IID_IContextMenu,(void**)&context)))return 16;
    const std::wstring root=std::wstring(argv[2])+L"\\shell-selection";CreateDirectoryW(root.c_str(),nullptr);
    struct Case {std::vector<std::wstring> ext;std::set<std::wstring> expected;};
    std::vector<Case> cases={
        {{L"pdf"},{L"docx",L"pptx",L"txt"}},
        {{L"docx"},{L"pdf",L"odt",L"rtf",L"txt"}},
        {{L"odt"},{L"pdf",L"docx",L"rtf",L"txt"}},
        {{L"rtf"},{L"pdf",L"docx",L"odt",L"txt"}},
        {{L"txt"},{L"pdf",L"docx",L"odt",L"rtf"}},
        {{L"pptx"},{L"pdf",L"odp"}},{{L"odp"},{L"pdf",L"pptx"}},
        {{L"xlsx"},{L"pdf",L"ods"}},{{L"ods"},{L"pdf",L"xlsx"}},
        {{L"png"},{L"pdf",L"jpg",L"webp",L"bmp",L"tiff"}},
        {{L"jpg"},{L"pdf",L"png",L"webp",L"bmp",L"tiff"}},
        {{L"jpeg"},{L"pdf",L"png",L"webp",L"bmp",L"tiff"}},
        {{L"webp"},{L"pdf",L"png",L"jpg",L"bmp",L"tiff"}},
        {{L"bmp"},{L"pdf",L"png",L"jpg",L"webp",L"tiff"}},
        {{L"tif"},{L"pdf",L"png",L"jpg",L"webp",L"bmp"}},
        {{L"tiff"},{L"pdf",L"png",L"jpg",L"webp",L"bmp"}},
        {{L"DOCX",L"PNG"},{L"pdf"}},{{L"pdf",L"docx"},{L"txt"}},
        {{L"xlsx",L"odp"},{L"pdf"}},{{L"odt",L"docx"},{L"pdf",L"rtf",L"txt"}},
        {{L"png",L"jpg"},{L"pdf",L"webp",L"bmp",L"tiff"}},
        {{L"exe"},{}},{{L"pdf",L"xlsx"},{}}
    };
    unsigned passed=0;
    for(const auto& test:cases) {
        std::vector<std::wstring> paths;
        for(const auto& ext:test.ext) {
            auto path=root+L"\\selection."+ext;
            auto file=CreateFileW(path.c_str(),GENERIC_WRITE,0,nullptr,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
            if(file==INVALID_HANDLE_VALUE)return 7;CloseHandle(file);paths.push_back(path);
        }
        auto items=selection(paths);if(!items)return 8;
        EXPCMDSTATE state=ECS_HIDDEN;
        if(FAILED(command->GetState(items,FALSE,&state)) || (state==ECS_ENABLED)!=!test.expected.empty())return 9;
        IEnumExplorerCommand* children=nullptr;
        if(FAILED(command->EnumSubCommands(&children)))return 10;
        ULONG fetched=0;IExplorerCommand* child=nullptr;std::set<std::wstring> visible;
        while(children->Next(1,&child,&fetched)==S_OK) {
            if(FAILED(child->GetState(items,FALSE,&state)))return 11;
            if(state==ECS_ENABLED) {
                PWSTR title=nullptr;if(FAILED(child->GetTitle(items,&title)))return 12;
                auto text=std::wstring(title);CoTaskMemFree(title);
                const auto prefix=std::wstring(L"Converter para ");
                if(text.rfind(prefix,0)!=0)return 13;
                text=text.substr(prefix.size());for(auto& c:text)c=towlower(c);visible.insert(text);
            }
            child->Release();
        }
        children->Release();items->Release();
        if(visible!=test.expected){std::printf("FAIL selection %u\n",passed+1);return 14;}
        auto data=selectionData(root,paths);if(!data)return 17;
        auto initialized=initializer->Initialize(nullptr,data,nullptr);data->Release();if(FAILED(initialized))return 18;
        auto menu=CreatePopupMenu();auto inserted=context->QueryContextMenu(menu,0,100,1000,CMF_NORMAL);
        if(FAILED(inserted) || (GetMenuItemCount(menu)!=0)!=!test.expected.empty())return 19;
        std::set<std::wstring> classic;
        const std::vector<std::wstring> orderedTargets={L"pdf",L"docx",L"pptx",L"odt",L"rtf",L"txt",L"odp",L"xlsx",L"ods",L"png",L"jpg",L"webp",L"bmp",L"tiff"};
        if(!test.expected.empty()) {
            wchar_t title[100];GetMenuStringW(menu,0,title,100,MF_BYPOSITION);
            if(std::wstring(title)!=L"Converter arquivo")return 20;
            auto submenu=GetSubMenu(menu,0);
            for(int n=0;n<GetMenuItemCount(submenu);++n) {
                GetMenuStringW(submenu,n,title,100,MF_BYPOSITION);
                std::wstring text(title);if(text.rfind(L"Converter para ",0)!=0)return 21;
                text=text.substr(15);for(auto& c:text)c=towlower(c);classic.insert(text);
                auto offset=GetMenuItemID(submenu,n)-100;
                if(offset>=orderedTargets.size() || orderedTargets[offset]!=text)return 22;
            }
        }
        DestroyMenu(menu);
        if(classic!=test.expected)return 23;
        menu=CreatePopupMenu();context->QueryContextMenu(menu,0,100,1000,CMF_DEFAULTONLY);
        if(GetMenuItemCount(menu)!=0)return 24;DestroyMenu(menu);
        ++passed;
        for(const auto& path:paths)DeleteFileW(path.c_str());
    }
    CMINVOKECOMMANDINFO invalid={};invalid.cbSize=sizeof(invalid);invalid.lpVerb=MAKEINTRESOURCEA(1000);
    if(context->InvokeCommand(&invalid)!=E_INVALIDARG)return 25;
    if(initializer->Initialize(nullptr,nullptr,nullptr)!=E_INVALIDARG)return 26;
    RemoveDirectoryW(root.c_str());initializer->Release();context->Release();command->Release();factory->Release();
    if(canUnload()!=S_OK)return 15;
    FreeLibrary(dll);CoUninitialize();std::printf("%u ExplorerCommand selections, %u classic selections, default-only filtering, command IDs, invalid invocation and unload passed.\n",passed,passed);return 0;
}
