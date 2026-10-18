#define UNICODE
#define _UNICODE
#include <windows.h>
#include <shobjidl.h>
#include <shlobj.h>
#include <cstdio>

static const CLSID clsid={0x43cf6a2e,0x7404,0x4f6b,{0x9d,0x87,0xc8,0x69,0xf5,0xa1,0x1c,0x70}};
int wmain(int argc,wchar_t** argv) {
    if(argc!=5)return 2;
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    auto dll=LoadLibraryW(argv[1]);if(!dll)return 3;
    auto getClass=(HRESULT(WINAPI*)(REFCLSID,REFIID,void**))GetProcAddress(dll,"DllGetClassObject");
    auto canUnload=(HRESULT(WINAPI*)())GetProcAddress(dll,"DllCanUnloadNow");
    if(!getClass || !canUnload)return 4;
    IClassFactory* factory=nullptr;
    if(FAILED(getClass(clsid,IID_IClassFactory,(void**)&factory)))return 5;
    IExplorerCommand* command=nullptr;
    if(FAILED(factory->CreateInstance(nullptr,IID_IExplorerCommand,(void**)&command)))return 6;
    for(int index=2;index<5;++index) {
        IShellItem* item=nullptr;IShellItemArray* items=nullptr;
        if(FAILED(SHCreateItemFromParsingName(argv[index],nullptr,IID_IShellItem,(void**)&item)))return 7;
        if(FAILED(SHCreateShellItemArrayFromShellItem(item,IID_IShellItemArray,(void**)&items)))return 8;
        item->Release();EXPCMDSTATE state=ECS_HIDDEN;
        if(FAILED(command->GetState(items,FALSE,&state))||state!=ECS_ENABLED)return 9;
        IEnumExplorerCommand* children=nullptr;
        if(FAILED(command->EnumSubCommands(&children)))return 10;
        ULONG fetched=0;IExplorerCommand* child=nullptr;int visible=0;
        while(children->Next(1,&child,&fetched)==S_OK) {
            if(FAILED(child->GetState(items,FALSE,&state)))return 11;
            if(state==ECS_ENABLED)visible++;
            child->Release();
        }
        children->Release();items->Release();
        if(visible!=(index==2?2:1))return 12;
        std::printf("PASS %d valid child command(s) for selection %d\n",visible,index-1);
    }
    command->Release();factory->Release();
    if(canUnload()!=S_OK)return 13;
    FreeLibrary(dll);CoUninitialize();std::puts("4 native COM assertions passed.");return 0;
}
